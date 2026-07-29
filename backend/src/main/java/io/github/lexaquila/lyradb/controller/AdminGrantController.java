package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.Grant;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 当前工作空间的数据源授权管理。
 */
@RestController
@RequestMapping("/admin/grants")
public class AdminGrantController {

    private final GrantService grantService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminGrantController(GrantService grantService, SecurityUtil securityUtil,
                                AuditService auditService) {
        this.grantService = grantService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        return grantService.listByWorkspace(securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        Grant grant = grantService.create(
                workspaceId,
                (String) body.get("dataSourceId"),
                (String) body.get("userId"),
                (String) body.get("grantedSourceName"),
                (String) body.get("allowedSchemas"),
                (String) body.get("allowedTables"),
                (String) body.get("blockedTables"),
                (String) body.get("sqlCapability"),
                body.get("maxRowsPerQuery") instanceof Number number ? number.intValue() : 10000,
                parseDateTime(body.get("expiresAt")));
        auditService.recordCurrent(workspaceId, "GRANT_CREATE",
                grant.getDataSourceId(), grant.getGrantedSourceName(), true, null);
        return Map.of("id", grant.getId(), "success", true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        Grant grant = grantService.getById(id);
        securityUtil.requireResourceInWorkspace(grant.getWorkspaceId(), session);
        grantService.delete(id);
        auditService.recordCurrent(grant.getWorkspaceId(), "GRANT_DELETE",
                grant.getDataSourceId(), grant.getGrantedSourceName(), true, null);
        return Map.of("success", true);
    }

    private static LocalDateTime parseDateTime(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString());
        } catch (Exception exception) {
            throw new IllegalArgumentException("expiresAt 必须为 ISO-8601 本地时间");
        }
    }
}
