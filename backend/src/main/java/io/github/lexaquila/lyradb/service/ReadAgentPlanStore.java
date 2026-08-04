package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.tool.AgentPermissionEnvelope;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;
import io.github.lexaquila.lyradb.ai.tool.AgentToolCall;
import io.github.lexaquila.lyradb.ai.tool.AgentToolDefinition;
import io.github.lexaquila.lyradb.ai.tool.AgentToolEffect;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.AiAgentRun;
import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 加密持久化的只读 Agent 计划状态机。
 *
 * <p>计划正文仅在有效期内以 AES-GCM 密文落库；确认时通过悲观锁一次性
 * 消费。执行节点写入数据库，任一节点发起取消后由所有者轮询并精确取消
 * 本地 JDBC Statement。</p>
 */
@Component
public class ReadAgentPlanStore {

    private static final AgentToolDefinition READ_QUERY_TOOL =
            new AgentToolDefinition(
                    "sql.execute.readonly", "1",
                    "执行经 AST、Grant、行数和成本预算约束的只读 SQL",
                    AgentToolEffect.READ_DATA, true, true);

    private final AiAgentRunRepository repository;
    private final CredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public ReadAgentPlanStore(
            AiAgentRunRepository repository,
            CredentialService credentialService,
            ObjectMapper objectMapper,
            AppProperties properties) {
        this.repository = repository;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        String configured = properties.getAi().getExecutionNodeId();
        if (configured != null && configured.length() > 128) {
            throw new IllegalStateException("AI 执行节点 ID 不得超过 128 字符");
        }
        this.nodeId = configured == null || configured.isBlank()
                ? "node-" + UUID.randomUUID()
                : configured.trim();
    }

    /** 与运行索引处于同一事务时，计划与预检令牌可原子提交。 */
    @Transactional
    public void put(AiAgentRun run, PlanSession session) {
        if (run == null || run.getId() == null
                || !run.getId().equals(session.runId())) {
            throw new IllegalArgumentException("Agent 运行索引与计划不一致");
        }
        try {
            PlanPayload payload = new PlanPayload(
                    session.sql(), session.defaultDatabase(),
                    session.resources().stream().sorted().toList(),
                    session.envelope().maxEstimatedCostMicros(),
                    session.call().callId(), session.call().runId(),
                    session.call().argumentsSha256());
            String json = objectMapper.writeValueAsString(payload);
            run.setPlanPayloadCiphertext(
                    credentialService.encryptValue(json));
            run.setPlanConsumed(false);
            run.setCancelRequested(false);
            run.setExecutionNodeId(null);
            repository.saveAndFlush(run);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法持久化加密 Agent 计划", exception);
        }
    }

    /** 悲观锁保证计划跨线程、跨节点只能确认一次。 */
    @Transactional(noRollbackFor = PlanStateException.class)
    public PlanSession claim(
            String runId,
            String workspaceId,
            String userId,
            String confirmedPlanSha256) {
        AiAgentRun run = requireOwnedForUpdate(runId, workspaceId, userId);
        if (!constantTimeEquals(
                run.getPlanSha256(), confirmedPlanSha256)) {
            throw new IllegalArgumentException("计划摘要不一致，必须重新预检");
        }
        Instant expiresAt = run.getExpiresAt().toInstant(ZoneOffset.UTC);
        if (!Instant.now().isBefore(expiresAt)) {
            run.setStatus("EXPIRED");
            run.setPlanConsumed(true);
            run.setPlanPayloadCiphertext(null);
            repository.saveAndFlush(run);
            throw new PlanStateException("Agent 计划已过期，请重新生成");
        }
        if (!"PLANNED".equals(run.getStatus()) || run.isPlanConsumed()) {
            throw new PlanStateException(
                    "Agent 计划当前不可执行: " + run.getStatus());
        }
        PlanPayload payload = decryptPayload(run.getPlanPayloadCiphertext());
        validatePayload(run, payload);
        Set<String> resources = Set.copyOf(payload.resources());
        AgentPermissionEnvelope envelope = new AgentPermissionEnvelope(
                run.getUserId(), run.getWorkspaceId(), run.getGrantId(),
                Set.of(READ_QUERY_TOOL.name()), resources,
                run.getRequestedRows(), payload.maxEstimatedCostMicros(),
                expiresAt, AgentRiskLevel.R2);
        AgentToolCall call = new AgentToolCall(
                payload.callId(), payload.callRunId(),
                READ_QUERY_TOOL.name(), resources,
                run.getRequestedRows(), run.getEstimatedCostMicros(),
                payload.argumentsSha256());
        PlanSession session = new PlanSession(
                run.getId(), run.getWorkspaceId(), run.getUserId(),
                run.getGrantId(), run.getGrantedSourceName(),
                run.getQuestionSha256(), payload.sql(), run.getSqlSha256(),
                payload.defaultDatabase(), run.getPlanSha256(), resources,
                envelope, READ_QUERY_TOOL, call, expiresAt);
        run.setPlanConsumed(true);
        run.setStatus("RUNNING");
        run.setExecutionNodeId(nodeId);
        run.setErrorMessage(null);
        repository.saveAndFlush(run);
        return session;
    }

    @Transactional
    public CancelDecision requestCancel(
            String runId, String workspaceId, String userId) {
        AiAgentRun run = requireOwnedForUpdate(runId, workspaceId, userId);
        boolean dispatchLocally = false;
        switch (run.getStatus()) {
            case "PLANNED" -> {
                run.setStatus("CANCELLED");
                run.setCancelRequested(true);
                run.setPlanConsumed(true);
                run.setPlanPayloadCiphertext(null);
            }
            case "RUNNING", "CANCEL_REQUESTED" -> {
                run.setStatus("CANCEL_REQUESTED");
                run.setCancelRequested(true);
                dispatchLocally = nodeId.equals(run.getExecutionNodeId());
            }
            default -> {
                // 已处于终态；幂等返回真实状态，不改变历史结果。
            }
        }
        repository.saveAndFlush(run);
        return new CancelDecision(
                run.getId(), run.getStatus(), run.getGrantedSourceName(),
                dispatchLocally);
    }

    @Transactional
    public void complete(
            PlanSession session,
            QueryResult result,
            String contextReceiptJson,
            String toolTraceJson) {
        AiAgentRun run = requireOwnedForUpdate(
                session.runId(), session.workspaceId(), session.userId());
        run.setStatus("COMPLETED");
        run.setResultRows(result.getTotalRows());
        run.setElapsedMs(result.getElapsedMs());
        run.setContextReceiptJson(contextReceiptJson);
        run.setToolTraceJson(toolTraceJson);
        run.setPlanPayloadCiphertext(null);
        run.setErrorMessage(null);
        repository.saveAndFlush(run);
    }

    @Transactional
    public String fail(PlanSession session, String errorMessage) {
        AiAgentRun run = requireOwnedForUpdate(
                session.runId(), session.workspaceId(), session.userId());
        String status = run.isCancelRequested()
                || "CANCEL_REQUESTED".equals(run.getStatus())
                ? "CANCELLED" : "FAILED";
        run.setStatus(status);
        run.setPlanPayloadCiphertext(null);
        run.setErrorMessage(errorMessage);
        repository.saveAndFlush(run);
        return status;
    }

    /** 所有者节点轮询数据库中的跨节点取消标记。 */
    @Scheduled(fixedDelayString = "${app.ai.cancel-poll-interval-ms:1000}")
    void dispatchCrossNodeCancellations() {
        for (AiAgentRun run : repository
                .findTop100ByExecutionNodeIdAndStatusAndCancelRequestedTrue(
                        nodeId, "CANCEL_REQUESTED")) {
            StatementRegistry.cancelExecution(executionId(run.getId()));
        }
    }

    /** 将无人确认的过期计划转为可观察终态。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    @Transactional
    void expireUnclaimedPlans() {
        for (AiAgentRun run : repository.findTop100ByStatusAndExpiresAtBefore(
                "PLANNED", LocalDateTime.now(ZoneOffset.UTC))) {
            run.setStatus("EXPIRED");
            run.setPlanConsumed(true);
            run.setPlanPayloadCiphertext(null);
            repository.save(run);
        }
    }

    String nodeId() {
        return nodeId;
    }

    private AiAgentRun requireOwnedForUpdate(
            String runId, String workspaceId, String userId) {
        AiAgentRun run = repository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent 计划不存在或已失效"));
        if (!run.getWorkspaceId().equals(workspaceId)
                || !run.getUserId().equals(userId)) {
            throw new AccessDeniedException(
                    "Agent 计划不属于当前用户和工作空间");
        }
        return run;
    }

    private PlanPayload decryptPayload(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new PlanStateException("Agent 计划正文不存在或已清除");
        }
        try {
            return objectMapper.readValue(
                    credentialService.decryptValue(ciphertext),
                    PlanPayload.class);
        } catch (PlanStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 计划密文无法验证", exception);
        }
    }

    private static void validatePayload(
            AiAgentRun run, PlanPayload payload) {
        if (payload.sql() == null
                || !run.getSqlSha256().equals(AiDigest.sha256(payload.sql()))
                || payload.resources() == null
                || payload.resources().isEmpty()
                || payload.maxEstimatedCostMicros() < 0
                || payload.callId() == null
                || payload.callRunId() == null
                || payload.argumentsSha256() == null
                || !payload.argumentsSha256().equals(AiDigest.sha256(
                        payload.sql() + "\n" + safe(
                                payload.defaultDatabase())))) {
            throw new IllegalStateException("Agent 计划持久载荷校验失败");
        }
    }

    private static boolean constantTimeEquals(
            String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.trim().toLowerCase(Locale.ROOT)
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static String executionId(String runId) {
        return "ai-read-" + runId;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record PlanPayload(
            String sql,
            String defaultDatabase,
            List<String> resources,
            long maxEstimatedCostMicros,
            String callId,
            String callRunId,
            String argumentsSha256) {
    }

    public record PlanSession(
            String runId,
            String workspaceId,
            String userId,
            String grantId,
            String grantedSourceName,
            String questionSha256,
            String sql,
            String sqlSha256,
            String defaultDatabase,
            String planSha256,
            Set<String> resources,
            AgentPermissionEnvelope envelope,
            AgentToolDefinition tool,
            AgentToolCall call,
            Instant expiresAt) {
    }

    public record CancelDecision(
            String runId,
            String status,
            String grantedSourceName,
            boolean dispatchLocally) {
    }

    public static final class PlanStateException
            extends IllegalStateException {
        public PlanStateException(String message) {
            super(message);
        }
    }
}
