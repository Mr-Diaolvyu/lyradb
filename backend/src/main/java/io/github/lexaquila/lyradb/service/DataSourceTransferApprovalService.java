package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 批量数据源配置导出的专用审批适配器。
 *
 * <p>审批载荷只记录服务端解析的不可变数据源引用、凭据模式和明文风险确认；
 * 前端名称、导出口令与真实凭据永远不进入审批记录。</p>
 */
@Service
public class DataSourceTransferApprovalService {

    public static final String OPERATION = "DATASOURCE_EXPORT";
    private static final int MAX_DATA_SOURCES = 100;
    private static final int MAX_ACTIVE_PER_USER_WORKSPACE = 50;
    private static final int MAX_REASON_CHARS = 500;
    private static final int APPLICATION_TTL_HOURS = 72;
    private static final int EXECUTION_STALE_HOURS = 6;
    private static final String PAYLOAD_INDEX_PURPOSE =
            "datasource-export-approval-payload-v2";
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "dataSourceRefs", "credentialMode", "plaintextRiskConfirmed");
    private static final Set<String> REF_FIELDS = Set.of("id", "displayName");

    private final ApprovalRequestRepository repository;
    private final DataSourceRepository dataSourceRepository;
    private final CredentialService credentialService;
    private final ApprovalSecurityContextService securityContextService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public DataSourceTransferApprovalService(
            ApprovalRequestRepository repository,
            DataSourceRepository dataSourceRepository,
            CredentialService credentialService,
            ApprovalSecurityContextService securityContextService,
            EntityManager entityManager,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.dataSourceRepository = dataSourceRepository;
        this.credentialService = credentialService;
        this.securityContextService = securityContextService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApprovalRequest create(
            String workspaceId, User applicant, List<String> dataSourceIds,
            String credentialMode, boolean plaintextRiskConfirmed,
            String reason) {
        if (workspaceId == null || workspaceId.isBlank()
                || applicant == null || applicant.getId() == null) {
            throw new IllegalArgumentException("审批申请人与工作空间不能为空");
        }
        List<String> normalizedIds = normalizeIds(dataSourceIds);
        CredentialExportPolicy policy =
                normalizePolicy(credentialMode, plaintextRiskConfirmed);
        String normalizedReason = normalizeReason(reason);

        securityContextService.lockWorkspace(workspaceId);
        List<DataSourceRef> refs = verifiedRefs(
                workspaceId, normalizedIds);
        String canonicalPayload = canonicalPayload(
                refs, policy, plaintextRiskConfirmed);
        String payloadHash = credentialService.blindIndex(
                PAYLOAD_INDEX_PURPOSE, canonicalPayload);
        String securityContextHash =
                securityContextService.fingerprintDataSources(
                        workspaceId, normalizedIds);
        Optional<ApprovalRequest> duplicate = findExactActive(
                applicant.getId(), workspaceId, payloadHash);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        long active = repository.countByApplicantIdAndWorkspaceIdAndStatusIn(
                applicant.getId(), workspaceId, List.of("PENDING", "APPROVED"));
        if (active >= MAX_ACTIVE_PER_USER_WORKSPACE) {
            throw new IllegalStateException(
                    "当前工作空间活动审批已达上限，请先处理已有申请");
        }

        ApprovalRequest approval = new ApprovalRequest();
        approval.setWorkspaceId(workspaceId);
        approval.setApplicantId(applicant.getId());
        approval.setApplicantName(applicant.getUsername());
        approval.setOperationType(OPERATION);
        approval.setGrantedSourceName("批量数据源配置（"
                + normalizedIds.size() + "）");
        approval.setSecurityContextHash(securityContextHash);
        approval.setPayloadJson(
                credentialService.encryptValue(canonicalPayload));
        approval.setPayloadHash(payloadHash);
        approval.setReason(normalizedReason);
        approval.setRiskScore(riskScore(policy));
        approval.setStatus("PENDING");
        approval.setExpiresAt(
                LocalDateTime.now().plusHours(APPLICATION_TTL_HOURS));
        return repository.save(approval);
    }

    /**
     * 原子消费已批准申请。调用者随后只能按返回的服务端载荷生成一次文件。
     */
    @Transactional
    public Claim claim(
            String approvalId, User applicant, String workspaceId) {
        if (approvalId == null || approvalId.isBlank()
                || applicant == null || applicant.getId() == null
                || workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("导出审批领取参数不完整");
        }
        LocalDateTime beforeLock = now();
        if (repository.expireByIdAndStatusBefore(
                approvalId, "APPROVED", beforeLock) > 0) {
            throw new RuntimeException("审批单已过期");
        }

        securityContextService.lockWorkspace(workspaceId);
        ApprovalRequest approval = repository.findByIdForUpdate(approvalId)
                .orElseThrow(() -> new RuntimeException(
                        "审批申请不存在: " + approvalId));
        entityManager.refresh(approval);
        LocalDateTime lockedNow = now();
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new RuntimeException(
                    "审批单不可执行，当前状态: " + approval.getStatus());
        }
        if (approval.getExpiresAt() != null
                && !approval.getExpiresAt().isAfter(lockedNow)) {
            throw new RuntimeException("审批单已过期");
        }
        if (!OPERATION.equals(approval.getOperationType())
                || !workspaceId.equals(approval.getWorkspaceId())
                || !applicant.getId().equals(approval.getApplicantId())) {
            throw new RuntimeException("审批单与当前用户或工作空间不匹配");
        }

        String storedPayload =
                credentialService.decryptValue(approval.getPayloadJson());
        Payload payload = parsePayload(storedPayload);
        String expectedPayload = canonicalPayload(
                payload.dataSourceRefs(), payload.credentialMode(),
                payload.plaintextRiskConfirmed());
        if (!constantTimeEquals(expectedPayload, storedPayload)) {
            throw new RuntimeException("审批载荷不是规范格式");
        }
        String currentContext =
                securityContextService.fingerprintDataSources(
                        workspaceId, payload.dataSourceIds());
        if (!constantTimeEquals(
                approval.getSecurityContextHash(), currentContext)) {
            throw new RuntimeException(
                    "审批后的数据源配置已变更，请重新申请");
        }
        if (!payload.dataSourceRefs().equals(verifiedRefs(
                workspaceId, payload.dataSourceIds()))) {
            throw new RuntimeException(
                    "审批后的数据源名称或范围已变更，请重新申请");
        }

        approval.setStatus("EXECUTING");
        approval.setExpiresAt(lockedNow.plusHours(EXECUTION_STALE_HOURS));
        repository.saveAndFlush(approval);
        return new Claim(
                approval, payload.dataSourceIds(), payload.credentialMode());
    }

    public Payload readPayload(ApprovalRequest approval) {
        if (approval == null || !OPERATION.equals(approval.getOperationType())) {
            throw new IllegalArgumentException("不是数据源导出审批");
        }
        return parsePayload(
                credentialService.decryptValue(approval.getPayloadJson()));
    }

    private Optional<ApprovalRequest> findExactActive(
            String applicantId, String workspaceId, String payloadHash) {
        Optional<ApprovalRequest> approved = repository
                .findFirstByApplicantIdAndWorkspaceIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicantId, workspaceId, OPERATION,
                        payloadHash, "APPROVED");
        return approved.isPresent() ? approved : repository
                .findFirstByApplicantIdAndWorkspaceIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
                        applicantId, workspaceId, OPERATION,
                        payloadHash, "PENDING");
    }

    private Payload parsePayload(String payloadJson) {
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    payloadJson,
                    new TypeReference<Map<String, Object>>() { });
            if (!PAYLOAD_FIELDS.equals(raw.keySet())) {
                throw new IllegalArgumentException(
                        "数据源导出审批载荷字段无效");
            }
            Object refsValue = raw.get("dataSourceRefs");
            if (!(refsValue instanceof List<?> values)) {
                throw new IllegalArgumentException(
                        "dataSourceRefs 必须是数组");
            }
            List<DataSourceRef> refs =
                    new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> ref)
                        || !REF_FIELDS.equals(ref.keySet())
                        || !(ref.get("id") instanceof String id)
                        || !(ref.get("displayName")
                        instanceof String displayName)) {
                    throw new IllegalArgumentException(
                            "dataSourceRefs 包含无效值");
                }
                refs.add(new DataSourceRef(id, displayName));
            }
            Object confirmed = raw.get("plaintextRiskConfirmed");
            if (!(confirmed instanceof Boolean riskConfirmed)) {
                throw new IllegalArgumentException(
                        "plaintextRiskConfirmed 必须是布尔值");
            }
            CredentialExportPolicy policy = normalizePolicy(
                    String.valueOf(raw.get("credentialMode")),
                    riskConfirmed);
            return new Payload(
                    normalizeRefs(refs), policy, riskConfirmed);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "数据源导出审批载荷无效", exception);
        }
    }

    private String canonicalPayload(
            List<DataSourceRef> refs, CredentialExportPolicy policy,
            boolean plaintextRiskConfirmed) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("dataSourceRefs", normalizeRefs(refs));
            value.put("credentialMode", policy.name());
            value.put("plaintextRiskConfirmed", plaintextRiskConfirmed);
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "无法构造数据源导出审批载荷", exception);
        }
    }

    private static List<String> normalizeIds(List<String> values) {
        if (values == null || values.isEmpty()
                || values.size() > MAX_DATA_SOURCES) {
            throw new IllegalArgumentException(
                    "数据源 ID 列表不能为空且最多 100 项");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()
                    || value.trim().length() > 36) {
                throw new IllegalArgumentException("数据源 ID 无效");
            }
            String id = value.trim();
            if (!normalized.contains(id)) {
                normalized.add(id);
            }
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private List<DataSourceRef> verifiedRefs(
            String workspaceId, List<String> normalizedIds) {
        Map<String, DataSource> byId = new LinkedHashMap<>();
        for (DataSource source :
                dataSourceRepository.findAllById(normalizedIds)) {
            if (!workspaceId.equals(source.getWorkspaceId())) {
                throw new RuntimeException(
                        "数据源不属于当前工作空间");
            }
            byId.put(source.getId(), source);
        }
        if (byId.size() != normalizedIds.size()) {
            throw new RuntimeException(
                    "部分数据源不存在或无权访问");
        }
        List<DataSourceRef> refs = new ArrayList<>(normalizedIds.size());
        for (String id : normalizedIds) {
            DataSource source = byId.get(id);
            refs.add(new DataSourceRef(
                    source.getId(), source.getDisplayName()));
        }
        return List.copyOf(refs);
    }

    private static List<DataSourceRef> normalizeRefs(
            List<DataSourceRef> values) {
        if (values == null || values.isEmpty()
                || values.size() > MAX_DATA_SOURCES) {
            throw new IllegalArgumentException(
                    "数据源引用列表不能为空且最多 100 项");
        }
        Map<String, DataSourceRef> byId = new LinkedHashMap<>();
        for (DataSourceRef value : values) {
            if (value == null) {
                throw new IllegalArgumentException("数据源引用无效");
            }
            DataSourceRef normalized = new DataSourceRef(
                    value.id(), value.displayName());
            if (byId.put(normalized.id(), normalized) != null) {
                throw new IllegalArgumentException(
                        "数据源引用不得重复");
            }
        }
        List<DataSourceRef> normalized =
                new ArrayList<>(byId.values());
        normalized.sort(java.util.Comparator.comparing(
                DataSourceRef::id));
        return List.copyOf(normalized);
    }

    private static CredentialExportPolicy normalizePolicy(
            String value, boolean plaintextRiskConfirmed) {
        final CredentialExportPolicy policy;
        try {
            policy = CredentialExportPolicy.valueOf(
                    value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "credentialMode 仅支持 OMIT/PLAINTEXT/PASSWORD_ENCRYPTED");
        }
        if (policy == CredentialExportPolicy.PLAINTEXT
                && !plaintextRiskConfirmed) {
            throw new IllegalArgumentException(
                    "明文导出必须显式确认凭据泄露风险");
        }
        if (policy != CredentialExportPolicy.PLAINTEXT
                && plaintextRiskConfirmed) {
            throw new IllegalArgumentException(
                    "非明文导出不得设置明文风险确认");
        }
        return policy;
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_REASON_CHARS) {
            throw new IllegalArgumentException(
                    "reason 不得超过 500 字符");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static int riskScore(CredentialExportPolicy policy) {
        return switch (policy) {
            case OMIT -> 10;
            case PASSWORD_ENCRYPTED -> 70;
            case PLAINTEXT -> 100;
        };
    }

    LocalDateTime now() {
        return LocalDateTime.now();
    }

    private static boolean constantTimeEquals(
            String expected, String actual) {
        byte[] left = expected == null ? new byte[0]
                : expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0]
                : actual.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    public record DataSourceRef(String id, String displayName) {
        public DataSourceRef {
            if (id == null || id.isBlank()
                    || id.trim().length() > 36) {
                throw new IllegalArgumentException("数据源 ID 无效");
            }
            if (displayName == null || displayName.isBlank()
                    || displayName.trim().length() > 100) {
                throw new IllegalArgumentException("数据源显示名无效");
            }
            id = id.trim();
            displayName = displayName.trim();
        }
    }

    public record Payload(
            List<DataSourceRef> dataSourceRefs,
            CredentialExportPolicy credentialMode,
            boolean plaintextRiskConfirmed) {
        public Payload {
            dataSourceRefs = normalizeRefs(dataSourceRefs);
        }

        public List<String> dataSourceIds() {
            return dataSourceRefs.stream()
                    .map(DataSourceRef::id).toList();
        }
    }

    public record Claim(
            ApprovalRequest approval,
            List<String> dataSourceIds,
            CredentialExportPolicy credentialMode) {
    }
}
