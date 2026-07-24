package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源授权管理（管理员）
 */
@RestController
@RequestMapping("/admin/grants")
public class AdminGrantController {

    private final GrantService grantService;
    private final SecurityUtil securityUtil;

    public AdminGrantController(GrantService grantService, SecurityUtil securityUtil) {
        this.grantService = grantService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "workspaceId", required = false) String workspaceId) {
        securityUtil.requireRole("DS_ADMIN");
        return grantService.listByWorkspace(workspaceId);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        securityUtil.requireRole("DS_ADMIN");
        String id = grantService.create(
                (String) body.get("workspaceId"),
                (String) body.get("dataSourceId"),
                (String) body.get("userId"),
                (String) body.get("grantedSourceName"),
                (String) body.get("allowedSchemas"),
                (String) body.get("allowedTables"),
                (String) body.get("blockedTables"),
                (String) body.get("sqlCapability"),
                body.get("maxRowsPerQuery") instanceof Number n ? n.intValue() : 10000,
                null
        ).getId();
        return Map.of("id", id, "success", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        grantService.delete(id);
        return Map.of("success", true);
    }
}
