package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.service.AdminGrantScopeService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员选择授权范围时读取当前工作空间数据源的库和表名称。 */
@RestController
@RequestMapping("/admin/grants/scope")
public class AdminGrantScopeController {
    private final AdminGrantScopeService scopeService;
    private final DataSourceService dataSourceService;
    private final SecurityUtil securityUtil;

    public AdminGrantScopeController(AdminGrantScopeService scopeService,
                                     DataSourceService dataSourceService,
                                     SecurityUtil securityUtil) {
        this.scopeService = scopeService;
        this.dataSourceService = dataSourceService;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/{dataSourceId}")
    public AdminGrantScopeService.ScopeOptions options(
            @PathVariable String dataSourceId, HttpSession session) throws Exception {
        securityUtil.requireRole("DS_ADMIN");
        DataSource source = dataSourceService.getEntity(dataSourceId);
        securityUtil.requireResourceInWorkspace(source.getWorkspaceId(), session);
        return scopeService.options(source);
    }
}
