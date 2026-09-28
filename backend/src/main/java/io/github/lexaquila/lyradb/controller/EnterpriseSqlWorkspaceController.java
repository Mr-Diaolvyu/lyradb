package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.EnterpriseSqlWorkspaceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ent")
public class EnterpriseSqlWorkspaceController {
    private final EnterpriseSqlWorkspaceService service;
    private final SecurityUtil securityUtil;

    public EnterpriseSqlWorkspaceController(EnterpriseSqlWorkspaceService service,
                                            SecurityUtil securityUtil) {
        this.service = service;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/scripts")
    public List<Map<String, Object>> scripts(HttpSession session) {
        return service.scripts(securityUtil.requireCurrentWorkspace(session),
                securityUtil.requireCurrentUser().getId());
    }

    @PostMapping("/scripts")
    public Map<String, Object> save(@RequestBody Map<String, String> body,
                                    HttpSession session) {
        return service.save(securityUtil.requireCurrentWorkspace(session),
                securityUtil.requireCurrentUser().getId(), body.get("id"),
                body.get("grantedSourceName"), body.get("title"), body.get("sql"));
    }

    @DeleteMapping("/scripts/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        service.delete(securityUtil.requireCurrentWorkspace(session),
                securityUtil.requireCurrentUser().getId(), id);
        return Map.of("success", true);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(HttpSession session) {
        return service.history(securityUtil.requireCurrentWorkspace(session),
                securityUtil.requireCurrentUser().getId());
    }
}
