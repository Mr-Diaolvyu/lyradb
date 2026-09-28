package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiBackgroundTaskService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/ai/tasks")
public class AiBackgroundTaskController {
    private final AiBackgroundTaskService tasks;
    private final SecurityUtil security;
    public AiBackgroundTaskController(AiBackgroundTaskService tasks, SecurityUtil security) {
        this.tasks = tasks;
        this.security = security;
    }
    @PostMapping
    public AiBackgroundTaskService.View submit(@RequestBody AiBackgroundTaskService.Request request, HttpSession session) {
        return tasks.submit(security.requireCurrentWorkspace(session), request);
    }
    @GetMapping
    public List<AiBackgroundTaskService.View> list(HttpSession session) {
        return tasks.list(security.requireCurrentWorkspace(session));
    }
}
