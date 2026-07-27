package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.BackgroundTask;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.BackgroundTaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 后台查询任务控制器（迭代二 E1）
 *
 * <ul>
 * <li>POST /api/tasks - 提交后台查询 {connectionId, connectionName?, sql,
 * defaultDatabase?, force?}</li>
 * <li>GET /api/tasks - 任务列表</li>
 * <li>GET /api/tasks/{id}/result - 回取结果</li>
 * <li>POST /api/tasks/{id}/cancel - 取消运行中任务</li>
 * <li>DELETE /api/tasks/{id} - 删除终态任务记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/tasks")
public class BackgroundTaskController {

    private final BackgroundTaskService taskService;

    public BackgroundTaskController(BackgroundTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public BackgroundTask submit(@RequestBody Map<String, String> body) {
        String connectionId = body.get("connectionId");
        String sql = body.get("sql");
        if (connectionId == null || sql == null || sql.isBlank()) {
            throw new RuntimeException("connectionId 和 sql 必填");
        }
        return taskService.submit(
                connectionId,
                body.getOrDefault("connectionName", connectionId),
                sql,
                body.get("defaultDatabase"),
                Boolean.parseBoolean(body.get("force")));
    }

    @GetMapping
    public List<BackgroundTask> list() {
        return taskService.list();
    }

    @GetMapping("/{id}/result")
    public QueryResult result(@PathVariable String id) {
        return taskService.getResult(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return Map.of("cancelled", taskService.cancel(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        taskService.remove(id);
        return Map.of("success", true);
    }
}
