
package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.AuditLog;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户及当前工作空间审计查询。
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditService auditService;
    private final SecurityUtil securityUtil;

    public AuditController(AuditService auditService, SecurityUtil securityUtil) {
        this.auditService = auditService;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/mine")
    public Page<AuditLog> mine(@RequestParam(value = "page", defaultValue = "0") int page,
                               @RequestParam(value = "size", defaultValue = "50") int size,
                               HttpSession session) {
        String userId = securityUtil.requireCurrentUser().getId();
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        return auditService.listMine(
                userId, workspaceId, pageRequest(page, size));
    }

    @GetMapping("/workspace")
    public Page<AuditLog> workspace(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpSession session) {
        if (!securityUtil.hasRole("DS_ADMIN")
                && !securityUtil.hasRole("STEWARD")
                && !securityUtil.hasRole("AUDITOR")) {
            throw new AccessDeniedException(
                    "当前工作空间需要 DS_ADMIN、STEWARD 或 AUDITOR 角色");
        }
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        return auditService.listByWorkspace(workspaceId, pageRequest(page, size));
    }

    private static PageRequest pageRequest(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
