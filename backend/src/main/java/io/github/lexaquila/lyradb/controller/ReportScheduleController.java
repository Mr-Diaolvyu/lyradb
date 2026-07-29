package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.ReportRun;
import io.github.lexaquila.lyradb.model.entity.ReportSchedule;
import io.github.lexaquila.lyradb.service.ReportScheduleService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 定时报表订阅控制器。所有者与工作空间只从服务端会话获取。
 */
@RestController
@RequestMapping("/reports")
public class ReportScheduleController {

    private final ReportScheduleService reportScheduleService;
    private final SecurityUtil securityUtil;
    private final AppProperties appProperties;

    public ReportScheduleController(ReportScheduleService reportScheduleService,
            SecurityUtil securityUtil, AppProperties appProperties) {
        this.reportScheduleService = reportScheduleService;
        this.securityUtil = securityUtil;
        this.appProperties = appProperties;
    }

    @GetMapping
    public List<ReportSchedule> list(HttpSession session) {
        RequestScope scope = requestScope(session);
        return reportScheduleService.list(scope.ownerUsername(), scope.workspaceId());
    }

    @PostMapping
    public ReportSchedule save(@RequestBody ReportSchedule schedule, HttpSession session) {
        RequestScope scope = requestScope(session);
        return reportScheduleService.save(schedule, scope.ownerUsername(), scope.workspaceId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        reportScheduleService.delete(id, scope.ownerUsername(), scope.workspaceId());
        return Map.of("success", true);
    }

    @GetMapping("/{id}/runs")
    public List<ReportRun> runs(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        return reportScheduleService.listRuns(id, scope.ownerUsername(), scope.workspaceId());
    }

    @PostMapping("/{id}/trigger")
    public ReportRun trigger(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        return reportScheduleService.triggerNow(id, scope.ownerUsername(), scope.workspaceId());
    }

    private RequestScope requestScope(HttpSession session) {
        boolean enterprise = "enterprise".equalsIgnoreCase(appProperties.getEdition());
        String username = securityUtil.currentUsername();
        if (username == null && enterprise) {
            throw new AccessDeniedException("必须登录后才能访问报表订阅");
        }
        if (username == null) {
            username = "personal";
        }
        Object selected = session != null ? session.getAttribute("currentWorkspaceId") : null;
        String workspaceId = selected != null ? selected.toString() : null;
        if (enterprise && (workspaceId == null || workspaceId.isBlank())) {
            throw new AccessDeniedException("请先选择工作空间");
        }
        return new RequestScope(username, enterprise ? workspaceId : "personal");
    }

    private record RequestScope(String ownerUsername, String workspaceId) {
    }
}
