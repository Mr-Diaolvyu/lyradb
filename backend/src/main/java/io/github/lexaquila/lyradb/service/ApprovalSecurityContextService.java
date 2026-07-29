
package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.MaskingRuleRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成审批时安全上下文的不可逆指纹，并在治理配置变化时使未执行审批失效。
 *
 * <p>指纹覆盖真实连接参数、授权边界和当前工作空间对该数据源生效的脱敏规则。
 * 审批记录只保存 SHA-256，不暴露凭证。配置变更与失效操作必须处于同一事务。</p>
 */
@Service
public class ApprovalSecurityContextService {

    private final DataSourceRepository dataSourceRepository;
    private final MaskingRuleRepository maskingRuleRepository;
    private final ApprovalPolicyRepository approvalPolicyRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final ObjectMapper objectMapper;

    public ApprovalSecurityContextService(
            DataSourceRepository dataSourceRepository,
            MaskingRuleRepository maskingRuleRepository,
            ApprovalPolicyRepository approvalPolicyRepository,
            WorkspaceRepository workspaceRepository,
            ApprovalRequestRepository approvalRepository,
            ObjectMapper objectMapper) {
        this.dataSourceRepository = dataSourceRepository;
        this.maskingRuleRepository = maskingRuleRepository;
        this.approvalPolicyRepository = approvalPolicyRepository;
        this.workspaceRepository = workspaceRepository;
        this.approvalRepository = approvalRepository;
        this.objectMapper = objectMapper;
    }

    public String fingerprint(Grant grant) {
        if (grant == null || grant.getId() == null) {
            throw new IllegalArgumentException("审批必须绑定已持久化的授权");
        }
        DataSource dataSource = dataSourceRepository.findById(grant.getDataSourceId())
                .orElseThrow(() -> new RuntimeException(
                        "审批绑定的数据源不存在: " + grant.getDataSourceId()));
        if (!grant.getWorkspaceId().equals(dataSource.getWorkspaceId())) {
            throw new RuntimeException("审批授权与数据源工作空间不一致");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("dataSource", dataSourceContext(dataSource));
        context.put("grant", grantContext(grant));
        context.put("maskingRules", maskingContext(
                grant.getWorkspaceId(), grant.getDataSourceId()));
        context.put("approvalPolicy", policyContext(grant.getWorkspaceId()));
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(context);
            return hex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成审批安全上下文指纹", exception);
        }
    }

    /**
     * 数据源安全配置变化时使待审批/已审批单失效；若已有外部执行正在进行则拒绝变更。
     */
    public void invalidateForDataSource(String workspaceId, String dataSourceId) {
        lockWorkspace(workspaceId);
        approvalRepository.invalidateActionableByDataSource(
                dataSourceId, LocalDateTime.now());
        requireNoExecutingForDataSource(dataSourceId);
    }

    /**
     * 删除授权前使其待审批/已审批单失效；执行中的授权禁止删除。
     */
    public void invalidateForGrant(String workspaceId, String grantId) {
        lockWorkspace(workspaceId);
        approvalRepository.invalidateActionableByGrant(
                grantId, LocalDateTime.now());
        if (approvalRepository.existsByGrantIdAndStatus(
                grantId, "EXECUTING")) {
            throw new IllegalStateException(
                    "该授权存在执行中的审批任务，暂不能删除");
        }
    }

    /**
     * 任一脱敏规则变化都可能改变结果暴露范围，因此使工作空间内全部待执行审批失效。
     */
    public void invalidateForMasking(String workspaceId) {
        lockWorkspace(workspaceId);
        approvalRepository.invalidateActionableByWorkspace(
                workspaceId, LocalDateTime.now());
        if (approvalRepository.existsByWorkspaceIdAndStatus(
                workspaceId, "EXECUTING")) {
            throw new IllegalStateException(
                    "当前工作空间存在执行中的审批任务，暂不能修改脱敏规则");
        }
    }

    /** 审批策略仅影响尚未消费的审批，不改变已经 EXECUTING 的外部执行上下文。 */
    public void invalidateForApprovalPolicy(String workspaceId) {
        lockWorkspace(workspaceId);
        approvalRepository.invalidateActionableByWorkspace(
                workspaceId, LocalDateTime.now());
    }

    /** 必须在事务内调用；锁一直持有到事务提交/回滚。 */
    public void lockWorkspace(String workspaceId) {
        workspaceRepository.findByIdForGovernanceUpdate(workspaceId)
                .orElseThrow(() -> new RuntimeException(
                        "工作空间不存在: " + workspaceId));
    }

    private void requireNoExecutingForDataSource(String dataSourceId) {
        if (approvalRepository.existsByDataSourceIdAndStatus(
                dataSourceId, "EXECUTING")) {
            throw new IllegalStateException(
                    "该数据源存在执行中的审批任务，暂不能修改或删除");
        }
    }

    private static Map<String, Object> dataSourceContext(DataSource source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", source.getId());
        value.put("workspaceId", source.getWorkspaceId());
        value.put("dbType", source.getDbType());
        value.put("connectionParamsJson", source.getConnectionParamsJson());
        return value;
    }

    private static Map<String, Object> grantContext(Grant grant) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", grant.getId());
        value.put("workspaceId", grant.getWorkspaceId());
        value.put("dataSourceId", grant.getDataSourceId());
        value.put("userId", grant.getUserId());
        value.put("grantedSourceName", grant.getGrantedSourceName());
        value.put("allowedSchemas", grant.getAllowedSchemas());
        value.put("allowedTables", grant.getAllowedTables());
        value.put("blockedTables", grant.getBlockedTables());
        value.put("sqlCapability", grant.getSqlCapability());
        value.put("maxRowsPerQuery", grant.getMaxRowsPerQuery());
        value.put("exportApprovedOnly", grant.isExportApprovedOnly());
        value.put("expiresAt", grant.getExpiresAt());
        return value;
    }

    private Map<String, Object> policyContext(String workspaceId) {
        ApprovalPolicy policy = approvalPolicyRepository.findByWorkspaceId(workspaceId)
                .orElse(null);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("approverRole", policy == null || policy.getApproverRole() == null
                || policy.getApproverRole().isBlank()
                ? "STEWARD" : policy.getApproverRole().trim().toUpperCase());
        value.put("requireTwoApprovers", policy != null
                && policy.isRequireTwoApprovers());
        return value;
    }

    private List<Map<String, Object>> maskingContext(
            String workspaceId, String dataSourceId) {
        List<MaskingRule> rules = new ArrayList<>(
                maskingRuleRepository.findByWorkspaceIdAndDataSourceIdIsNull(
                        workspaceId));
        rules.addAll(maskingRuleRepository.findByWorkspaceIdAndDataSourceId(
                workspaceId, dataSourceId));
        rules.sort(Comparator.comparing(MaskingRule::getId));

        List<Map<String, Object>> values = new ArrayList<>(rules.size());
        for (MaskingRule rule : rules) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", rule.getId());
            value.put("dataSourceId", rule.getDataSourceId());
            value.put("tablePattern", rule.getTablePattern());
            value.put("columnPattern", rule.getColumnPattern());
            value.put("maskType", rule.getMaskType());
            value.put("enabled", rule.isEnabled());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }
}
