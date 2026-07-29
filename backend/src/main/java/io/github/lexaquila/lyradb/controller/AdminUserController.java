package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台用户及当前工作空间角色管理。
 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserService userService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminUserController(UserService userService, SecurityUtil securityUtil,
                               AuditService auditService) {
        this.userService = userService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        return userService.listAll().stream().map(user -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("displayName", user.getDisplayName());
            item.put("email", user.getEmail());
            item.put("enabled", user.isEnabled());
            item.put("roles", effectiveRoles(user, workspaceId));
            item.put("workspaceIds", userService.workspaceIds(user.getId()));
            return item;
        }).collect(Collectors.toList());
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");
        User user = userService.create(
                (String) body.get("username"),
                (String) body.get("password"),
                (String) body.get("displayName"),
                (String) body.get("email"),
                roles);
        userService.assignWorkspace(user.getUsername(), workspaceId, roles);
        auditService.recordCurrent(workspaceId, "USER_CREATE", null, user.getUsername(), true, null);
        return Map.of("id", user.getId(), "success", true);
    }

    @PutMapping("/{username}/workspace-roles")
    @Transactional
    public Map<String, Object> updateWorkspaceRoles(@PathVariable String username,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");
        userService.assignWorkspace(username, workspaceId, roles);
        auditService.recordCurrent(workspaceId, "USER_WORKSPACE_ROLES_UPDATE",
                null, username, true, null);
        return Map.of("success", true);
    }

    @PostMapping("/{username}/password")
    @Transactional
    public Map<String, Object> resetPassword(@PathVariable String username,
                                             @RequestBody Map<String, String> body,
                                             HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        userService.setPassword(username, body.get("newPassword"));
        auditService.recordCurrent(workspaceId, "USER_PASSWORD_RESET", null, username, true, null);
        return Map.of("success", true);
    }

    private Set<String> effectiveRoles(User user, String workspaceId) {
        Set<String> result = new LinkedHashSet<>();
        if (user.getRoles().contains("PLATFORM_ADMIN")) {
            result.add("PLATFORM_ADMIN");
        }
        result.addAll(userService.workspaceRoles(user.getId(), workspaceId));
        return result;
    }
}
