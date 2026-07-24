package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

/**
 * 审批策略管理（DS_ADMIN）—— 脱敏/双人审批/敏感表/阈值
 */
@RestController
@RequestMapping("/admin/approval-policy")
public class AdminApprovalPolicyController {

    private final ApprovalPolicyRepository repository;
    private final SecurityUtil securityUtil;

    public AdminApprovalPolicyController(ApprovalPolicyRepository repository, SecurityUtil securityUtil) {
        this.repository = repository;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public ApprovalPolicy get(@RequestParam(value = "workspaceId", required = false) String workspaceId) {
        securityUtil.requireRole("DS_ADMIN");
        return repository.findByWorkspaceId(workspaceId == null ? "" : workspaceId)
                .orElseGet(() -> {
                    ApprovalPolicy p = new ApprovalPolicy();
                    p.setWorkspaceId(workspaceId);
                    return p;
                });
    }

    @PutMapping
    public ApprovalPolicy save(@RequestBody ApprovalPolicy body) {
        securityUtil.requireRole("DS_ADMIN");
        ApprovalPolicy existing = repository.findByWorkspaceId(body.getWorkspaceId() == null ? "" : body.getWorkspaceId())
                .orElseGet(() -> {
                    ApprovalPolicy p = new ApprovalPolicy();
                    p.setWorkspaceId(body.getWorkspaceId());
                    return p;
                });
        existing.setAlwaysApproveExport(body.isAlwaysApproveExport());
        existing.setDmlRowThreshold(body.getDmlRowThreshold());
        existing.setAlwaysApproveMigration(body.isAlwaysApproveMigration());
        existing.setAlwaysApproveAiDml(body.isAlwaysApproveAiDml());
        existing.setSensitiveTables(body.getSensitiveTables());
        existing.setApproverRole(body.getApproverRole());
        existing.setRequireTwoApprovers(body.isRequireTwoApprovers());
        return repository.save(existing);
    }
}
