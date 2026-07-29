

package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作空间隔离的审批控制器。
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final GrantService grantService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public ApprovalController(ApprovalService approvalService, GrantService grantService,
                              SecurityUtil securityUtil, AuditService auditService) {
        this.approvalService = approvalService;
        this.grantService = grantService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = securityUtil.requireCurrentUser();
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        String grantedSourceName = stringValue(body.get("grantedSourceName"));
        Grant grant = grantService.resolveForUser(
                user.getId(), workspaceId, grantedSourceName);
        if (!workspaceId.equals(grant.getWorkspaceId())) {
            throw new RuntimeException("授权不属于当前工作空间");
        }
        ApprovalRequest created = approvalService.create(
                grant,
                user,
                stringValue(body.get("operationType")),
                stringValue(body.get("payloadJson")),
                stringValue(body.get("reason")));
        auditService.recordCurrent(workspaceId, "APPROVAL_CREATE",
                grant.getDataSourceId(), grant.getGrantedSourceName(), true, null);
        return approvalService.toView(created, true);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(value = "mine", defaultValue = "false") boolean mine,
            @RequestParam(value = "status", required = false) String status,
            HttpSession session) {
        User user = securityUtil.requireCurrentUser();
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        if (mine) {
            return approvalService.listMine(
                            user.getId(), workspaceId, status).stream()
                    .map(approval -> approvalService.toView(approval, false))
                    .toList();
        }
        requireApproverRole(workspaceId);
        return approvalService.listPending(workspaceId).stream()
                .map(approval -> approvalService.toView(approval, false))
                .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
            @PathVariable String id, HttpSession session) {
        ApprovalRequest approval =
                requireCurrentWorkspaceApproval(id, session);
        User current = securityUtil.requireCurrentUser();
        if (!current.getId().equals(approval.getApplicantId())) {
            requireApproverRole(approval.getWorkspaceId());
        }
        return approvalService.toView(approval, true);
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> pending(HttpSession session) {
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        requireApproverRole(workspaceId);
        return approvalService.listPending(workspaceId).stream()
                .map(approval -> approvalService.toView(approval, false))
                .toList();
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public Map<String, Object> approve(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, String> body,
                                       HttpSession session) {
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        requireApproverRole(workspaceId);
        String comment = body == null ? null : body.get("comment");
        ApprovalRequest updated = approvalService.approve(
                id, securityUtil.requireCurrentUser().getId(),
                workspaceId, comment);
        auditService.recordCurrent(workspaceId, "APPROVAL_APPROVE",
                updated.getDataSourceId(),
                updated.getGrantedSourceName(), true, null);
        return approvalService.toView(updated, true);
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public Map<String, Object> reject(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, String> body,
                                      HttpSession session) {
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        requireApproverRole(workspaceId);
        String comment = body == null ? null : body.get("comment");
        ApprovalRequest updated = approvalService.reject(
                id, securityUtil.requireCurrentUser().getId(),
                workspaceId, comment);
        auditService.recordCurrent(workspaceId, "APPROVAL_REJECT",
                updated.getDataSourceId(),
                updated.getGrantedSourceName(), true, null);
        return approvalService.toView(updated, true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> cancel(@PathVariable String id, HttpSession session) {
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        ApprovalRequest updated = approvalService.cancel(
                id, securityUtil.requireCurrentUser().getId(), workspaceId);
        auditService.recordCurrent(workspaceId, "APPROVAL_CANCEL",
                updated.getDataSourceId(),
                updated.getGrantedSourceName(), true, null);
        return approvalService.toView(updated, true);
    }

    private ApprovalRequest requireCurrentWorkspaceApproval(String id, HttpSession session) {
        ApprovalRequest approval = approvalService.get(id);
        securityUtil.requireResourceInWorkspace(approval.getWorkspaceId(), session);
        return approval;
    }

    private void requireApproverRole(String workspaceId) {
        String requiredRole = approvalService.requiredApproverRole(workspaceId);
        securityUtil.requireRole(requiredRole);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
