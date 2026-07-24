package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 审批控制器
 *
 * <p>POST /api/approvals · GET /api/approvals?mine=&status= · /pending ·
 * POST /{id}/approve · /{id}/reject · /{id}/execute · DELETE /{id}</p>
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final SecurityUtil securityUtil;

    public ApprovalController(ApprovalService approvalService, SecurityUtil securityUtil) {
        this.approvalService = approvalService;
        this.securityUtil = securityUtil;
    }

    @PostMapping
    public ApprovalRequest create(@RequestBody Map<String, Object> body) {
        return approvalService.create(
                (String) body.get("workspaceId"),
                securityUtil.currentUserId(),
                securityUtil.currentUsername(),
                (String) body.get("operationType"),
                (String) body.get("dataSourceId"),
                (String) body.get("grantedSourceName"),
                body.get("payloadJson") != null ? body.get("payloadJson").toString() : null,
                (String) body.get("reason"));
    }

    @GetMapping
    public List<ApprovalRequest> list(@RequestParam(value = "mine", defaultValue = "false") boolean mine,
                                      @RequestParam(value = "status", required = false) String status) {
        if (mine) return approvalService.listMine(securityUtil.currentUserId());
        if ("PENDING".equalsIgnoreCase(status)) return approvalService.listPending();
        return approvalService.listPending();
    }

    @GetMapping("/pending")
    public List<ApprovalRequest> pending() {
        return approvalService.listPending();
    }

    @PostMapping("/{id}/approve")
    public ApprovalRequest approve(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        securityUtil.requireRole("STEWARD");
        String comment = body != null ? body.get("comment") : null;
        return approvalService.approve(id, securityUtil.currentUserId(), comment);
    }

    @PostMapping("/{id}/reject")
    public ApprovalRequest reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        securityUtil.requireRole("STEWARD");
        String comment = body != null ? body.get("comment") : null;
        return approvalService.reject(id, securityUtil.currentUserId(), comment);
    }

    @PostMapping("/{id}/execute")
    public ApprovalRequest execute(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String result = body != null && body.get("result") != null ? body.get("result").toString() : "executed";
        boolean success = body == null || body.get("success") == null || Boolean.TRUE.equals(body.get("success"));
        return approvalService.markExecuted(id, result, success);
    }

    @DeleteMapping("/{id}")
    public ApprovalRequest cancel(@PathVariable String id) {
        return approvalService.cancel(id, securityUtil.currentUserId());
    }
}
