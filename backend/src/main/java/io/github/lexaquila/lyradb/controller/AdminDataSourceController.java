package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 真实数据源管理（管理员，DS_ADMIN/PLATFORM_ADMIN）
 *
 * <p>所有响应参数掩码（密码 ********）；明文仅在连接时解密。</p>
 */
@RestController
@RequestMapping("/admin/datasources")
public class AdminDataSourceController {

    private final DataSourceService dataSourceService;
    private final SecurityUtil securityUtil;

    public AdminDataSourceController(DataSourceService dataSourceService, SecurityUtil securityUtil) {
        this.dataSourceService = dataSourceService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "workspaceId", required = false) String workspaceId) {
        securityUtil.requireRole("DS_ADMIN");
        return dataSourceService.listMasked(workspaceId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        return dataSourceService.getMasked(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        securityUtil.requireRole("DS_ADMIN");
        String id = dataSourceService.create(
                (String) body.get("workspaceId"),
                (String) body.get("dbType"),
                (String) body.get("displayName"),
                (Map<String, Object>) body.get("params"),
                (String) body.get("description"),
                securityUtil.currentUserId()
        ).getId();
        return Map.of("id", id, "success", true);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        securityUtil.requireRole("DS_ADMIN");
        dataSourceService.update(id,
                (String) body.get("displayName"),
                (String) body.get("description"),
                (Map<String, Object>) body.get("params"));
        return Map.of("success", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        dataSourceService.delete(id);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        return dataSourceService.test(id);
    }
}
