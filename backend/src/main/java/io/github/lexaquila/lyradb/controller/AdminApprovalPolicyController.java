
package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.service.ApprovalSecurityContextService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 当前工作空间审批策略管理。
 */
@RestController
@RequestMapping("/admin/approval-policy")
public class AdminApprovalPolicyController {

    private static final Set<String> APPROVER_ROLES = Set.of("STEWARD", "DS_ADMIN");

    private final ApprovalPolicyRepository repository;
    private final ApprovalSecurityContextService approvalSecurityContextService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminApprovalPolicyController(ApprovalPolicyRepository repository,
                                         ApprovalSecurityContextService approvalSecurityContextService,
                                         SecurityUtil securityUtil,
                                         AuditService auditService) {
        this.repository = repository;
        this.approvalSecurityContextService = approvalSecurityContextService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public ApprovalPolicyView get(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        ApprovalPolicy policy = repository.findByWorkspaceId(workspaceId)
                .orElseGet(() -> emptyPolicy(workspaceId));
        return ApprovalPolicyView.from(policy);
    }

    @PutMapping
    @Transactional
    public ApprovalPolicyView save(@RequestBody ApprovalPolicyUpdateRequest body,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        String approverRole = body.approverRole() == null
                ? "STEWARD" : body.approverRole().trim().toUpperCase();
        if (!APPROVER_ROLES.contains(approverRole)) {
            throw new IllegalArgumentException("approverRole 仅支持 STEWARD/DS_ADMIN");
        }

        approvalSecurityContextService.invalidateForApprovalPolicy(workspaceId);
        ApprovalPolicy existing = repository.findByWorkspaceId(workspaceId)
                .orElseGet(() -> emptyPolicy(workspaceId));
        existing.setApproverRole(approverRole);
        existing.setRequireTwoApprovers(body.requireTwoApprovers());
        ApprovalPolicy saved = repository.saveAndFlush(existing);
        auditService.recordCurrent(workspaceId, "APPROVAL_POLICY_UPDATE",
                null, null, true, null);
        return ApprovalPolicyView.from(saved);
    }

    private static ApprovalPolicy emptyPolicy(String workspaceId) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspaceId);
        return policy;
    }

    /**
     * 只暴露审批状态机实际读取的字段，避免历史兼容列被误认为已生效配置。
     */
    public record ApprovalPolicyView(String approverRole, boolean requireTwoApprovers) {
        static ApprovalPolicyView from(ApprovalPolicy policy) {
            String role = policy.getApproverRole() == null || policy.getApproverRole().isBlank()
                    ? "STEWARD" : policy.getApproverRole().trim().toUpperCase();
            return new ApprovalPolicyView(role, policy.isRequireTwoApprovers());
        }
    }

    /**
     * 未声明字段（包括历史 alwaysApprove 系列、threshold、sensitiveTables）显式拒绝，
     * 使旧客户端得到 400，而不是显示“保存成功”但运行时不生效。
     */
    public record ApprovalPolicyUpdateRequest(String approverRole, boolean requireTwoApprovers) {
        @JsonAnySetter
        public void rejectUnsupportedField(String fieldName, Object ignoredValue) {
            throw new IllegalArgumentException("不支持的审批策略字段: " + fieldName);
        }
    }
}
