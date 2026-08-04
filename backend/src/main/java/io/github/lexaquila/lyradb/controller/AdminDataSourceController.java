package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 当前工作空间内的真实数据源管理。
 */
@RestController
@RequestMapping("/admin/datasources")
public class AdminDataSourceController {

    private final DataSourceService dataSourceService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminDataSourceController(DataSourceService dataSourceService, SecurityUtil securityUtil,
                                     AuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        return dataSourceService.listMasked(workspaceId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        requireResource(id, session);
        return dataSourceService.getMasked(id);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        DataSource dataSource = dataSourceService.create(
                workspaceId,
                (String) body.get("dbType"),
                (String) body.get("displayName"),
                params,
                (String) body.get("description"),
                securityUtil.currentUserId());
        auditService.recordCurrent(workspaceId, "DATA_SOURCE_CREATE",
                dataSource.getId(), dataSource.getDisplayName(), true, null);
        return Map.of("id", dataSource.getId(), "success", true);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestBody Map<String, Object> body,
                                      HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = requireResource(id, session).getWorkspaceId();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        DataSource updated = dataSourceService.update(
                id, (String) body.get("displayName"), (String) body.get("description"), params);
        auditService.recordCurrent(workspaceId, "DATA_SOURCE_UPDATE",
                id, updated.getDisplayName(), true, null);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/credentials/reveal")
    public ResponseEntity<Map<String, Object>> revealCredential(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        DataSource dataSource = requireResource(id, session);
        String field = body.get("field") == null
                ? null : body.get("field").toString();
        try {
            String value = dataSourceService.getPlaintextCredential(id, field);
            auditService.recordCurrent(dataSource.getWorkspaceId(),
                    "DATA_SOURCE_CREDENTIAL_REVEAL", id,
                    dataSource.getDisplayName(), true, null);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header("Pragma", "no-cache")
                    .body(Map.of("field", field, "value", value));
        } catch (RuntimeException exception) {
            auditService.recordCurrent(dataSource.getWorkspaceId(),
                    "DATA_SOURCE_CREDENTIAL_REVEAL", id,
                    dataSource.getDisplayName(), false, "凭据查看失败");
            throw exception;
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        DataSource dataSource = requireResource(id, session);
        dataSourceService.delete(id);
        auditService.recordCurrent(dataSource.getWorkspaceId(), "DATA_SOURCE_DELETE",
                id, dataSource.getDisplayName(), true, null);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        DataSource dataSource = requireResource(id, session);
        Map<String, Object> result = dataSourceService.test(id);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        auditService.recordCurrent(dataSource.getWorkspaceId(), "DATA_SOURCE_TEST",
                id, dataSource.getDisplayName(), success,
                success ? null : String.valueOf(result.get("message")));
        return result;
    }

    private DataSource requireResource(String id, HttpSession session) {
        DataSource dataSource = dataSourceService.getEntity(id);
        securityUtil.requireResourceInWorkspace(dataSource.getWorkspaceId(), session);
        return dataSource;
    }
}
