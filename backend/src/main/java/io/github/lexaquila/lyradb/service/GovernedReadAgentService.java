package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;
import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.model.EvidenceRef;
import io.github.lexaquila.lyradb.ai.model.EvidenceTrustLevel;
import io.github.lexaquila.lyradb.ai.tool.AgentPermissionEnvelope;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;
import io.github.lexaquila.lyradb.ai.tool.AgentToolCall;
import io.github.lexaquila.lyradb.ai.tool.AgentToolDefinition;
import io.github.lexaquila.lyradb.ai.tool.AgentToolEffect;
import io.github.lexaquila.lyradb.ai.tool.PolicyDecision;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentCancelView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentExecutionView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.AiAgentRun;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 两段式受治理只读 Agent：预检计划 → 用户确认计划摘要 → 再授权执行。 */
@Service
public class GovernedReadAgentService {

    private static final AgentToolDefinition READ_QUERY_TOOL =
            new AgentToolDefinition(
                    "sql.execute.readonly", "1",
                    "执行经 AST、Grant、行数和成本预算约束的只读 SQL",
                    AgentToolEffect.READ_DATA, true, true);

    private final GrantService grantService;
    private final SecurityUtil securityUtil;
    private final EnterpriseQueryService enterpriseQueryService;
    private final AiFeatureGate featureGate;
    private final AppProperties properties;
    private final AiAgentRunRepository repository;
    private final ReadAgentPlanStore planStore;
    private final AuditService auditService;
    private final DataSourceService dataSourceService;
    private final MaxComputePreflightStore maxComputePreflightStore;
    private final ObjectMapper objectMapper;
    private final AiOperationalMetrics metrics;

    public GovernedReadAgentService(
            GrantService grantService,
            SecurityUtil securityUtil,
            EnterpriseQueryService enterpriseQueryService,
            AiFeatureGate featureGate,
            AppProperties properties,
            AiAgentRunRepository repository,
            ReadAgentPlanStore planStore,
            AuditService auditService,
            DataSourceService dataSourceService,
            MaxComputePreflightStore maxComputePreflightStore,
            ObjectMapper objectMapper,
            AiOperationalMetrics metrics) {
        this.grantService = grantService;
        this.securityUtil = securityUtil;
        this.enterpriseQueryService = enterpriseQueryService;
        this.featureGate = featureGate;
        this.properties = properties;
        this.repository = repository;
        this.planStore = planStore;
        this.auditService = auditService;
        this.dataSourceService = dataSourceService;
        this.maxComputePreflightStore = maxComputePreflightStore;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public AiReadAgentPlanView plan(
            String workspaceId, AiReadAgentPlanRequest request) {
        featureGate.requireEnabled(AiFeature.GOVERNED_READ_AGENT);
        if (request == null) {
            throw new IllegalArgumentException("Agent 计划请求不能为空");
        }
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        String sourceName = requireText(
                request.getGrantedSourceName(), "逻辑数据源", 100);
        String question = requireText(request.getQuestion(), "问题", 20_000);
        String sql = requireText(request.getSql(), "SQL", 50_000);
        String defaultDatabase = optionalText(
                request.getDefaultDatabase(), 200);
        User user = securityUtil.requireCurrentUser();
        Grant grant = grantService.resolveForUser(
                user.getId(), workspace, sourceName);
        SqlParseUtil.Analysis analysis =
                enterpriseQueryService.authorizeReadOnly(
                        grant, sql, defaultDatabase);

        int productLimit = properties.getAi().getReadAgentMaxRows();
        int grantLimit = Math.max(1, grant.getMaxRowsPerQuery());
        int requested = request.getRequestedRows() == null
                ? Math.min(productLimit, grantLimit)
                : request.getRequestedRows();
        if (requested < 1) {
            throw new IllegalArgumentException("请求行数必须大于 0");
        }
        int maxRows = Math.min(requested,
                Math.min(productLimit, grantLimit));
        long estimatedCost = request.getEstimatedCostMicros() == null
                ? 0L : request.getEstimatedCostMicros();
        long costBudget = properties.getAi()
                .getReadAgentMaxEstimatedCostMicros();
        Instant expiresAt = Instant.now().plusSeconds(
                properties.getAi().getReadAgentPlanTtlSeconds());
        Set<String> resources = Set.copyOf(analysis.tables());
        AgentPermissionEnvelope envelope = new AgentPermissionEnvelope(
                user.getId(), workspace, grant.getId(),
                Set.of(READ_QUERY_TOOL.name()), resources,
                maxRows, costBudget, expiresAt, AgentRiskLevel.R2);
        String nonce = UUID.randomUUID().toString();
        AgentToolCall call = new AgentToolCall(
                "call-" + nonce, nonce, READ_QUERY_TOOL.name(), resources,
                maxRows, estimatedCost,
                AiDigest.sha256(sql + "\n" + safe(defaultDatabase)));
        PolicyDecision preflight = envelope.authorize(
                READ_QUERY_TOOL, call, Instant.now());
        if (!preflight.allowed()) {
            throw new IllegalArgumentException(
                    "Agent 计划未通过预检: " + preflight.code());
        }
        String sqlSha = AiDigest.sha256(sql);
        if (featureGate.isEnabled(AiFeature.MAXCOMPUTE_AGENT)) {
            DataSource dataSource = dataSourceService.getEntity(
                    grant.getDataSourceId());
            if (!workspace.equals(dataSource.getWorkspaceId())) {
                throw new IllegalStateException(
                        "授权与真实数据源工作空间不一致");
            }
            if ("MAXCOMPUTE".equalsIgnoreCase(dataSource.getDbType())) {
                maxComputePreflightStore.requireAndConsume(
                        request.getMaxComputePreflightSha256(), workspace,
                        user.getId(), grant.getId(), sqlSha, estimatedCost);
            }
        }

        String questionSha = AiDigest.sha256(question);
        String planSha = planDigest(
                nonce, workspace, user.getId(), grant.getId(), sourceName,
                sqlSha, defaultDatabase, resources, maxRows,
                estimatedCost, expiresAt);
        AiAgentRun run = new AiAgentRun();
        run.setWorkspaceId(workspace);
        run.setUserId(user.getId());
        run.setGrantId(grant.getId());
        run.setGrantedSourceName(sourceName);
        run.setStatus("PLANNED");
        run.setQuestionSha256(questionSha);
        run.setSqlSha256(sqlSha);
        run.setPlanSha256(planSha);
        run.setRequestedRows(maxRows);
        run.setEstimatedCostMicros(estimatedCost);
        run.setExpiresAt(LocalDateTime.ofInstant(
                expiresAt, ZoneOffset.UTC));
        AiAgentRun saved = repository.saveAndFlush(run);
        auditService.recordCurrent(workspace, "AI_READ_AGENT_PLAN",
                null, sourceName, true, null);

        ReadAgentPlanStore.PlanSession session =
                new ReadAgentPlanStore.PlanSession(
                        saved.getId(), workspace, user.getId(), grant.getId(),
                        sourceName, questionSha, sql, sqlSha, defaultDatabase,
                        planSha, resources, envelope, READ_QUERY_TOOL, call,
                        expiresAt);
        planStore.put(saved, session);
        metrics.record(AiOperationalMetrics.Operation.READ_AGENT_PLAN,
                true, 0L);
        return planView(session, maxRows, estimatedCost);
    }

    public AiReadAgentExecutionView execute(
            String workspaceId, String runId, String confirmedPlanSha) {
        long started = System.currentTimeMillis();
        featureGate.requireEnabled(AiFeature.GOVERNED_READ_AGENT);
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        User user = securityUtil.requireCurrentUser();
        ReadAgentPlanStore.PlanSession session = planStore.claim(
                requireText(runId, "运行 ID", 36), workspace,
                user.getId(), confirmedPlanSha);
        try {
            String regeneratedPlanSha = planDigest(
                    session.call().runId(), session.workspaceId(),
                    session.userId(), session.grantId(),
                    session.grantedSourceName(), session.sqlSha256(),
                    session.defaultDatabase(), session.resources(),
                    session.call().requestedRows(),
                    session.call().estimatedCostMicros(),
                    session.expiresAt());
            if (!constantTimeEquals(
                    session.planSha256(), regeneratedPlanSha)) {
                throw new IllegalStateException("持久计划绑定摘要校验失败");
            }
            Grant currentGrant = grantService.resolveForUser(
                    user.getId(), workspace,
                    session.grantedSourceName());
            if (!currentGrant.getId().equals(session.grantId())) {
                throw new IllegalStateException(
                        "授权已变化，必须重新生成 Agent 计划");
            }
            SqlParseUtil.Analysis currentAnalysis =
                    enterpriseQueryService.authorizeReadOnly(
                            currentGrant, session.sql(),
                            session.defaultDatabase());
            if (!Set.copyOf(currentAnalysis.tables()).equals(
                    session.resources())) {
                throw new IllegalStateException(
                        "SQL 资源范围已变化，必须重新生成计划");
            }
            PolicyDecision decision = session.envelope().authorize(
                    session.tool(), session.call(), Instant.now());
            if (!decision.allowed()) {
                throw new IllegalStateException(
                        "执行前权限包络拒绝: " + decision.code());
            }
            QueryResult result = enterpriseQueryService.executeQuery(
                    session.grantedSourceName(), session.sql(),
                    session.defaultDatabase(), executionId(session.runId()));
            AiContextReceipt receipt = executionReceipt(
                    session, result, System.currentTimeMillis() - started);
            completeRun(session, result, receipt);
            auditService.recordCurrent(workspace,
                    "AI_READ_AGENT_COMPLETE", null,
                    session.grantedSourceName(), true, null);
            metrics.record(AiOperationalMetrics.Operation.READ_AGENT_EXECUTE,
                    true, System.currentTimeMillis() - started);
            return new AiReadAgentExecutionView(
                    session.runId(), "COMPLETED", result, receipt);
        } catch (Exception exception) {
            String status = planStore.fail(
                    session, safeMessage(exception));
            auditService.recordCurrent(workspace,
                    "AI_READ_AGENT_" + status, null,
                    session.grantedSourceName(), false,
                    safeMessage(exception));
            metrics.record(AiOperationalMetrics.Operation.READ_AGENT_EXECUTE,
                    false, System.currentTimeMillis() - started);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("只读 Agent 执行失败", exception);
        }
    }

    public AiReadAgentCancelView cancel(
            String workspaceId, String runId) {
        long started = System.currentTimeMillis();
        featureGate.requireEnabled(AiFeature.GOVERNED_READ_AGENT);
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        User user = securityUtil.requireCurrentUser();
        ReadAgentPlanStore.CancelDecision decision =
                planStore.requestCancel(
                        requireText(runId, "运行 ID", 36), workspace,
                        user.getId());
        boolean dispatched = decision.dispatchLocally()
                && StatementRegistry.cancelExecution(
                        executionId(decision.runId()));
        auditService.recordCurrent(workspace, "AI_READ_AGENT_CANCEL",
                null, decision.grantedSourceName(), true, null);
        metrics.record(AiOperationalMetrics.Operation.READ_AGENT_CANCEL,
                true, System.currentTimeMillis() - started);
        return new AiReadAgentCancelView(
                decision.runId(), decision.status(), dispatched);
    }

    private void completeRun(
            ReadAgentPlanStore.PlanSession session,
            QueryResult result,
            AiContextReceipt receipt) {
        try {
            String receiptJson = objectMapper.writeValueAsString(receipt);
            String traceJson = objectMapper.writeValueAsString(List.of(
                    java.util.Map.of(
                            "tool", session.tool().name(),
                            "effect", session.tool().effect().name(),
                            "argumentsSha256",
                            session.call().argumentsSha256())));
            planStore.complete(
                    session, result, receiptJson, traceJson);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存 Agent 上下文回执", exception);
        }
    }

    private static AiContextReceipt executionReceipt(
            ReadAgentPlanStore.PlanSession session,
            QueryResult result,
            long elapsedMs) {
        String resultDigest = AiDigest.sha256(
                session.sqlSha256() + "\n"
                        + result.getTotalRows() + "\n"
                        + result.getColumns() + "\n" + elapsedMs);
        EvidenceRef resultEvidence = new EvidenceRef(
                session.runId() + "-result",
                AiEvidenceType.QUERY_RESULT,
                "受治理只读查询结果",
                "agent-run:" + session.runId(),
                resultDigest, Instant.now(),
                EvidenceTrustLevel.OBSERVED);
        return AiContextReceipt.create(
                session.runId(), session.workspaceId(),
                "GOVERNED_READ_AGENT", null, null, Instant.now(),
                List.of(resultEvidence),
                List.of("plan:" + session.planSha256(),
                        "grant:" + session.grantId(),
                        "risk:R2", "readonly-ast",
                        "row-limit:" + session.call().requestedRows(),
                        "masking-and-audit"),
                List.of("raw-question-not-persisted",
                        "raw-sql-encrypted-until-terminal"));
    }

    private static AiReadAgentPlanView planView(
            ReadAgentPlanStore.PlanSession session,
            int maxRows,
            long estimatedCost) {
        return new AiReadAgentPlanView(
                session.runId(), session.planSha256(),
                session.grantedSourceName(), session.sql(),
                session.defaultDatabase(), session.resources(), maxRows,
                estimatedCost, AgentRiskLevel.R2.name(),
                session.expiresAt(),
                List.of(
                        "重新解析 SQL 并确认仅为只读语句",
                        "将引用资源与当前用户 Grant 取交集",
                        "在行数和成本预算内执行，可按运行 ID 取消",
                        "应用现有脱敏与审计并生成 Context Receipt"),
                true);
    }

    private static String planDigest(
            String nonce, String workspaceId, String userId,
            String grantId, String sourceName, String sqlSha,
            String defaultDatabase, Set<String> resources,
            int maxRows, long estimatedCost, Instant expiresAt) {
        List<String> sortedResources = new ArrayList<>(resources);
        sortedResources.sort(Comparator.naturalOrder());
        return AiDigest.sha256(String.join("\n",
                nonce, workspaceId, userId, grantId, sourceName, sqlSha,
                safe(defaultDatabase), String.join(",", sortedResources),
                Integer.toString(maxRows), Long.toString(estimatedCost),
                expiresAt.toString()));
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

    private static String requireText(
            String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "可选文本长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : bounded(message, 2_000);
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength
                ? value : value.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
