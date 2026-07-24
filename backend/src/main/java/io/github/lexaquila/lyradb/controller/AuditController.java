package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.AuditLog;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/**
 * 审计查询控制器
 *
 * <p>GET /api/audit/mine · /api/audit/workspace?workspaceId= · /api/admin/audit</p>
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
                               @RequestParam(value = "size", defaultValue = "50") int size) {
        String uid = securityUtil.currentUserId();
        if (uid == null) throw new RuntimeException("未登录");
        return auditService.listMine(uid, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/workspace")
    public Page<AuditLog> workspace(@RequestParam("workspaceId") String workspaceId,
                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "50") int size) {
        securityUtil.requireRole("STEWARD");
        return auditService.listByWorkspace(workspaceId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
