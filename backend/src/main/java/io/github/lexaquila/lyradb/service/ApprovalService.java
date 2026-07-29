








package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 加密载荷、资源强绑定且只能消费一次的审批状态机。
 */
@Service
public class ApprovalService {

    private static final int APPROVAL_TTL_HOURS = 72;
    private static final int EXECUTE_WINDOW_HOURS = 24;
    private static final int EXECUTION_STALE_HOURS = 6;
    private static final int MAX_ACTIVE_PER_USER_WORKSPACE = 50;
    private static final int MAX_SQL_CHARS = 15_000;
    private static final int MAX_PAYLOAD_CHARS = 60_000;
    private static final int MAX_REASON_CHARS = 500;
    private static final int MAX_COMMENT_CHARS = 1_000;
    private static final String PAYLOAD_INDEX_PURPOSE = "approval-payload-v1";
    private static final Set<String> SUPPORTED_OPERATIONS = Set.of("EXPORT", "DANGEROUS_SQL");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("sql", "format", "defaultDatabase");

    private final ApprovalRequestRepository repository;
    private final ApprovalPolicyRepository policyRepository;
    private final CredentialService credentialService;
    private final ApprovalSecurityContextService securityContextService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public ApprovalService(ApprovalRequestRepository repository,
                           ApprovalPolicyRepository policyRepository,
                           CredentialService credentialService,
                           ApprovalSecurityContextService securityContextService,
                           EntityManager entityManager,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.policyRepository = policyRepository;
        this.credentialService = credentialService;
        this.securityContextService = securityContextService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    public ApprovalPolicy policyOf(String workspaceId) {
        return policyRepository.findByWorkspaceId(workspaceId).orElse(new ApprovalPolicy());
    }

    @Transactional
    public ApprovalRequest create(Grant grant, User applicant, String operationType,
                                  String payloadJson, String reason) {
        if (grant == null || applicant == null || !applicant.getId().equals(grant.getUserId())) {
            throw new RuntimeException("审批申请与当前授权不匹配");
        }
        String operation = normalizeOperation(operationType);
        if (payloadJson == null || payloadJson.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("审批载荷为空或超过长度限制");
        }
        String canonicalPayload = canonicalize(operation, payloadJson);
        String normalizedReason = boundedInput(reason, MAX_REASON_CHARS, "reason");
        String payloadHash = credentialService.blindIndex(
                PAYLOAD_INDEX_PURPOSE, canonicalPayload);
        securityContextService.lockWorkspace(grant.getWorkspaceId());

        Optional<ApprovalRequest> duplicate = findExactActive(
                applicant.getId(), grant, operation, payloadHash);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        long active = repository.countByApplicantIdAndWorkspaceIdAndStatusIn(
                applicant.getId(), grant.getWorkspaceId(),
                List.of("PENDING", "APPROVED"));
        if (active >= MAX_ACTIVE_PER_USER_WORKSPACE) {
            throw new IllegalStateException("当前工作空间活动审批已达上限，请先处理已有申请");
        }

        ApprovalRequest approval = new ApprovalRequest();
        approval.setWorkspaceId(grant.getWorkspaceId());
        approval.setApplicantId(applicant.getId());
        approval.setApplicantName(applicant.getUsername());
        approval.setOperationType(operation);
        approval.setDataSourceId(grant.getDataSourceId());
        approval.setGrantId(grant.getId());
        approval.setGrantedSourceName(grant.getGrantedSourceName());
        approval.setSecurityContextHash(securityContextService.fingerprint(grant));
        approval.setPayloadJson(credentialService.encryptValue(canonicalPayload));
        approval.setPayloadHash(payloadHash);
        approval.setReason(normalizedReason);
        approval.setStatus("PENDING");
        approval.setExpiresAt(LocalDateTime.now().plusHours(APPROVAL_TTL_HOURS));
        return repository.save(approval);
    }

    @Transactional
    public ApprovalRequest createDangerousSql(Grant grant, User applicant, String sql,
                                              String defaultDatabase, String reason) {
        return create(grant, applicant, "DANGEROUS_SQL",
                canonicalPayload(sql, null, defaultDatabase), reason);
    }

    public List<ApprovalRequest> listMine(
            String applicantId, String workspaceId, String status) {
        return status == null || status.isBlank()
                ? repository.findTop100ByApplicantIdAndWorkspaceIdOrderByCreatedAtDesc(
                        applicantId, workspaceId)
                : repository.findTop100ByApplicantIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(
                        applicantId, workspaceId,
                        status.trim().toUpperCase(Locale.ROOT));
    }

    public List<ApprovalRequest> listPending(String workspaceId) {
        return repository.findTop100ByWorkspaceIdAndStatusOrderByCreatedAtDesc(
                workspaceId, "PENDING");
    }

    public List<ApprovalRequest> listByWorkspace(String workspaceId) {
        return repository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    public Optional<ApprovalRequest> findActiveMatching(
            User applicant, Grant grant, String operationType,
            String sql, String format, String defaultDatabase) {
        String operation = normalizeOperation(operationType);
        String canonical = canonicalize(operation,
                canonicalPayload(sql, format, defaultDatabase));
        String payloadHash = credentialService.blindIndex(
                PAYLOAD_INDEX_PURPOSE, canonical);
        Optional<ApprovalRequest> approved = repository
                .findFirstByApplicantIdAndWorkspaceIdAndGrantIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicant.getId(), grant.getWorkspaceId(), grant.getId(),
                        operation, payloadHash, "APPROVED");
        return approved.isPresent() ? approved : repository
                .findFirstByApplicantIdAndWorkspaceIdAndGrantIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicant.getId(), grant.getWorkspaceId(), grant.getId(),
                        operation, payloadHash, "PENDING");
    }

    public String requiredApproverRole(String workspaceId) {
        String role = policyOf(workspaceId).getApproverRole();
        return role == null || role.isBlank() ? "STEWARD" : role.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional
    public ApprovalRequest approve(String id, String approverId, String workspaceId, String comment) {
        LocalDateTime now = LocalDateTime.now();
        expirePendingBeforeLock(id, now);
        ApprovalRequest approval = getForUpdate(id);
        requireWorkspace(approval, workspaceId);
        comment = boundedInput(comment, MAX_COMMENT_CHARS, "comment");
        requirePendingAndNotExpired(approval, now);
        if (approverId.equals(approval.getApplicantId())) {
            throw new RuntimeException("申请人不能审批自己的申请");
        }

        ApprovalPolicy policy = policyOf(approval.getWorkspaceId());
        if (policy.isRequireTwoApprovers()) {
            List<String> existing = approval.getApproverIds() == null
                    || approval.getApproverIds().isBlank()
                    ? List.of() : Arrays.asList(approval.getApproverIds().split(","));
            if (existing.contains(approverId)) {
                throw new RuntimeException("你已审批过，需另一位审批人");
            }
            approval.setApproverCount(existing.size() + 1);
            approval.setApproverIds(existing.isEmpty()
                    ? approverId : String.join(",", existing) + "," + approverId);
            approval.setApproverId(approverId);
            approval.setApproverComment(comment);
            if (approval.getApproverCount() >= 2) {
                approval.setStatus("APPROVED");
                approval.setExpiresAt(LocalDateTime.now().plusHours(EXECUTE_WINDOW_HOURS));
            }
        } else {
            approval.setStatus("APPROVED");
            approval.setApproverId(approverId);
            approval.setApproverCount(1);
            approval.setApproverIds(approverId);
            approval.setApproverComment(comment);
            approval.setExpiresAt(LocalDateTime.now().plusHours(EXECUTE_WINDOW_HOURS));
        }
        return repository.saveAndFlush(approval);
    }

    @Transactional
    public ApprovalRequest reject(String id, String approverId, String workspaceId, String comment) {
        comment = boundedInput(comment, MAX_COMMENT_CHARS, "comment");
        LocalDateTime now = LocalDateTime.now();
        expirePendingBeforeLock(id, now);
        ApprovalRequest approval = getForUpdate(id);
        requireWorkspace(approval, workspaceId);
        requirePendingAndNotExpired(approval, now);
        if (approverId.equals(approval.getApplicantId())) {
            throw new RuntimeException("申请人不能驳回自己的申请");
        }
        approval.setStatus("REJECTED");
        approval.setApproverId(approverId);
        approval.setApproverComment(comment);
        return repository.saveAndFlush(approval);
    }

    /**
     * 对申请人、工作空间、授权、SQL、格式、默认库做完整比对，并在同一数据库锁内
     * 把 APPROVED 原子转换为 EXECUTING。
     */
    @Transactional
    public ApprovalRequest claimForExecution(String id, User applicant, Grant grant,
                                             String operationType, String sql,
                                             String format, String defaultDatabase) {
        LocalDateTime now = LocalDateTime.now();
        if (repository.expireByIdAndStatusBefore(id, "APPROVED", now) > 0) {
            throw new RuntimeException("审批单已过期");
        }
        securityContextService.lockWorkspace(grant.getWorkspaceId());
        ApprovalRequest approval = getForUpdate(id);
        String operation = normalizeOperation(operationType);
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new RuntimeException("审批单不可执行，当前状态: " + approval.getStatus());
        }
        if (approval.getExpiresAt() != null && !approval.getExpiresAt().isAfter(now)) {
            throw new RuntimeException("审批单已过期");
        }
        if (!applicant.getId().equals(approval.getApplicantId())
                || !grant.getUserId().equals(applicant.getId())
                || !grant.getWorkspaceId().equals(approval.getWorkspaceId())
                || !grant.getDataSourceId().equals(approval.getDataSourceId())
                || !grant.getId().equals(approval.getGrantId())
                || !grant.getGrantedSourceName().equals(approval.getGrantedSourceName())
                || !operation.equals(approval.getOperationType())) {
            throw new RuntimeException("审批单与当前用户或授权资源不匹配");
        }
        String expected = canonicalize(operation,
                canonicalPayload(sql, format, defaultDatabase));
        String actual = credentialService.decryptValue(approval.getPayloadJson());
        if (!constantTimeEquals(expected, actual)) {
            throw new RuntimeException("执行请求与审批载荷不一致");
        }
        String currentSecurityContext = securityContextService.fingerprint(grant);
        if (!constantTimeEquals(
                approval.getSecurityContextHash(), currentSecurityContext)) {
            throw new RuntimeException("审批后的数据源、授权或脱敏配置已变更，请重新申请");
        }
        approval.setStatus("EXECUTING");
        approval.setExpiresAt(now.plusHours(EXECUTION_STALE_HOURS));
        return repository.saveAndFlush(approval);
    }

    @Transactional
    public ApprovalRequest markExecutionResult(String id, boolean success, String result) {
        ApprovalRequest approval = getForUpdate(id);
        if (!"EXECUTING".equals(approval.getStatus())) {
            throw new RuntimeException("审批单未处于执行中状态: " + approval.getStatus());
        }
        approval.setStatus(success ? "DONE" : "FAILED");
        approval.setExecutedAt(LocalDateTime.now());
        approval.setExecutionResult(result == null ? null
                : result.substring(0, Math.min(result.length(), 4000)));
        return repository.saveAndFlush(approval);
    }

    @Transactional
    public ApprovalRequest markExecutionUnknown(String id, String result) {
        ApprovalRequest approval = getForUpdate(id);
        if (!"EXECUTING".equals(approval.getStatus())) {
            throw new RuntimeException(
                    "审批单未处于执行中状态: " + approval.getStatus());
        }
        approval.setStatus("EXECUTION_UNKNOWN");
        approval.setExecutedAt(LocalDateTime.now());
        String message = result == null
                ? "外部数据库执行结果未知，禁止自动重试"
                : result;
        approval.setExecutionResult(
                message.substring(0, Math.min(message.length(), 4000)));
        return repository.saveAndFlush(approval);
    }

    @Transactional
    public ApprovalRequest cancel(
            String id, String applicantId, String workspaceId) {
        ApprovalRequest approval = getForUpdate(id);
        requireWorkspace(approval, workspaceId);
        if (!applicantId.equals(approval.getApplicantId())) {
            throw new RuntimeException("仅申请人可撤销");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new RuntimeException("当前状态不可撤销: " + approval.getStatus());
        }
        approval.setStatus("CANCELLED");
        return repository.saveAndFlush(approval);
    }

    public boolean payloadMatches(ApprovalRequest approval, String operationType, String sql,
                                  String format, String defaultDatabase) {
        String expected = canonicalize(operationType,
                canonicalPayload(sql, format, defaultDatabase));
        return constantTimeEquals(expected, credentialService.decryptValue(approval.getPayloadJson()));
    }

    public Map<String, Object> toView(ApprovalRequest approval, boolean includePayload) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", approval.getId());
        view.put("workspaceId", approval.getWorkspaceId());
        view.put("applicantId", approval.getApplicantId());
        view.put("applicantName", approval.getApplicantName());
        view.put("operationType", approval.getOperationType());
        view.put("grantId", approval.getGrantId());
        view.put("grantedSourceName", approval.getGrantedSourceName());
        if (includePayload) {
            view.put("payloadJson", credentialService.decryptValue(approval.getPayloadJson()));
        }
        view.put("reason", approval.getReason());
        view.put("status", approval.getStatus());
        view.put("approverId", approval.getApproverId());
        view.put("approverCount", approval.getApproverCount());
        view.put("approverComment", approval.getApproverComment());
        view.put("expiresAt", approval.getExpiresAt());
        view.put("executedAt", approval.getExecutedAt());
        view.put("executionResult", approval.getExecutionResult());
        view.put("createdAt", approval.getCreatedAt());
        return view;
    }

    public ApprovalRequest get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("审批申请不存在: " + id));
    }

    private ApprovalRequest getForUpdate(String id) {
        ApprovalRequest approval = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new RuntimeException(
                        "审批申请不存在: " + id));
        /*
         * 悲观锁查询可能命中当前事务一级缓存中的旧实体。锁已经由上面的
         * SELECT FOR UPDATE 持有，此处强制从数据库刷新，避免旧状态覆盖
         * 其他事务已提交的 APPROVED/EXECUTING 等终态。
         */
        entityManager.refresh(approval);
        return approval;
    }

    private void expirePendingBeforeLock(String id, LocalDateTime now) {
        if (repository.expireByIdAndStatusBefore(id, "PENDING", now) > 0) {
            throw new RuntimeException("审批申请已过期");
        }
    }

    private void requirePendingAndNotExpired(
            ApprovalRequest approval, LocalDateTime now) {
        if (!"PENDING".equals(approval.getStatus())) {
            throw new RuntimeException("申请当前状态不可审批: " + approval.getStatus());
        }
        if (approval.getExpiresAt() != null
                && !approval.getExpiresAt().isAfter(now)) {
            throw new RuntimeException("审批申请已过期");
        }
    }

    private static void requireWorkspace(ApprovalRequest approval, String workspaceId) {
        if (!approval.getWorkspaceId().equals(workspaceId)) {
            throw new RuntimeException("审批申请不属于当前工作空间");
        }
    }

    private String canonicalize(String operationType, String payloadJson) {
        String operation = normalizeOperation(operationType);
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    payloadJson, new TypeReference<Map<String, Object>>() { });
            if (!PAYLOAD_FIELDS.containsAll(payload.keySet())) {
                throw new IllegalArgumentException("审批载荷包含不支持的字段");
            }
            Object sqlValue = payload.get("sql");
            String sql = sqlValue == null ? null : sqlValue.toString();
            if (sql == null || sql.isBlank() || sql.length() > MAX_SQL_CHARS) {
                throw new IllegalArgumentException(
                        "审批载荷 sql 必填且不得超过 " + MAX_SQL_CHARS + " 字符");
            }
            String format = payload.get("format") == null
                    ? null : payload.get("format").toString();
            String defaultDatabase = payload.get("defaultDatabase") == null
                    ? null : payload.get("defaultDatabase").toString();
            if ("EXPORT".equals(operation)) {
                format = format == null || format.isBlank()
                        ? "csv" : format.trim().toLowerCase(Locale.ROOT);
                if (!Set.of("csv", "json").contains(format)) {
                    throw new IllegalArgumentException("导出格式仅支持 csv/json");
                }
            } else if (format != null && !format.isBlank()) {
                throw new IllegalArgumentException("危险 SQL 审批不接受 format");
            } else {
                format = null;
            }
            return canonicalPayload(sql, format, defaultDatabase);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("审批 payloadJson 必须是合法 JSON 对象", exception);
        }
    }

    private String canonicalPayload(String sql, String format, String defaultDatabase) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sql", sql);
            if (format != null && !format.isBlank()) {
                payload.put("format", format.trim().toLowerCase(Locale.ROOT));
            }
            if (defaultDatabase != null && !defaultDatabase.isBlank()) {
                payload.put("defaultDatabase", defaultDatabase.trim());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法构造审批载荷", exception);
        }
    }

    private Optional<ApprovalRequest> findExactActive(
            String applicantId, Grant grant,
            String operation, String payloadHash) {
        Optional<ApprovalRequest> approved = repository
                .findFirstByApplicantIdAndWorkspaceIdAndGrantIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicantId, grant.getWorkspaceId(), grant.getId(),
                        operation, payloadHash, "APPROVED");
        return approved.isPresent() ? approved : repository
                .findFirstByApplicantIdAndWorkspaceIdAndGrantIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicantId, grant.getWorkspaceId(), grant.getId(),
                        operation, payloadHash, "PENDING");
    }

    private static String boundedInput(
            String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 不得超过 " + maxLength + " 字符");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeOperation(String value) {
        String operation = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("当前仅支持 EXPORT/DANGEROUS_SQL 审批");
        }
        return operation;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected == null ? new byte[0]
                : expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0]
                : actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
