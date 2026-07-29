package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.BackgroundTask;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.BackgroundTaskService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 后台查询任务控制器。所有访问都由服务端会话推导所有者与工作空间，
 * 不接受客户端伪造的 owner 字段。
 */
@RestController
@RequestMapping("/tasks")
public class BackgroundTaskController {

    private final BackgroundTaskService taskService;
    private final SecurityUtil securityUtil;
    private final AppProperties appProperties;

    public BackgroundTaskController(BackgroundTaskService taskService,
            SecurityUtil securityUtil, AppProperties appProperties) {
        this.taskService = taskService;
        this.securityUtil = securityUtil;
        this.appProperties = appProperties;
    }

    @PostMapping
    public BackgroundTask submit(@RequestBody Map<String, String> body, HttpSession session) {
        String connectionId = body.get("connectionId");
        String sql = body.get("sql");
        if (connectionId == null || connectionId.isBlank() || sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("connectionId 和 sql 必填");
        }
        RequestScope scope = requestScope(session);
        return taskService.submit(scope.ownerUsername(), scope.workspaceId(),
                connectionId, body.getOrDefault("connectionName", connectionId), sql,
                body.get("defaultDatabase"), Boolean.parseBoolean(body.get("force")));
    }

    @GetMapping
    public List<BackgroundTask> list(HttpSession session) {
        RequestScope scope = requestScope(session);
        return taskService.list(scope.ownerUsername(), scope.workspaceId());
    }

    @GetMapping("/{id}/result")
    public QueryResult result(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        return taskService.getResult(id, scope.ownerUsername(), scope.workspaceId());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        return Map.of("cancelled",
                taskService.cancel(id, scope.ownerUsername(), scope.workspaceId()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id, HttpSession session) {
        RequestScope scope = requestScope(session);
        taskService.remove(id, scope.ownerUsername(), scope.workspaceId());
        return Map.of("success", true);
    }

    private RequestScope requestScope(HttpSession session) {
        boolean enterprise = "enterprise".equalsIgnoreCase(appProperties.getEdition());
        String username = securityUtil.currentUsername();
        if (username == null && enterprise) {
            throw new AccessDeniedException("必须登录后才能访问后台任务");
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
