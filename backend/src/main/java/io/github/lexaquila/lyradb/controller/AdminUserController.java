package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.AdminUserLifecycleService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AdminUserLifecycleService lifecycleService;

    public AdminUserController(UserService userService, SecurityUtil securityUtil,
                               AuditService auditService,
                               AdminUserLifecycleService lifecycleService) {
        this.userService = userService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
        this.lifecycleService = lifecycleService;
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

    @PutMapping("/{username}/roles")
    @Transactional
    public Map<String, Object> updateRoles(@PathVariable String username,
                                            @RequestBody Map<String, Object> body,
                                            HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");
        userService.updateRoles(username, workspaceId, roles, securityUtil.currentUserId());
        auditService.recordCurrent(workspaceId, "USER_ROLES_UPDATE",
                null, username, true, null);
        return Map.of("success", true);
    }

    @PostMapping("/{userId}/freeze")
    @Transactional
    public Map<String, Object> freeze(@PathVariable String userId, HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        lifecycleService.setFrozen(userId, securityUtil.currentUserId(), true);
        auditService.recordCurrent(workspaceId, "USER_FREEZE", null, userId, true, null);
        return Map.of("success", true);
    }

    @PostMapping("/{userId}/unfreeze")
    @Transactional
    public Map<String, Object> unfreeze(@PathVariable String userId, HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        lifecycleService.setFrozen(userId, securityUtil.currentUserId(), false);
        auditService.recordCurrent(workspaceId, "USER_UNFREEZE", null, userId, true, null);
        return Map.of("success", true);
    }

    @GetMapping("/{userId}/scripts")
    public List<Map<String, Object>> scripts(@PathVariable String userId,
                                              HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        return lifecycleService.scripts(
                securityUtil.requireCurrentWorkspace(session), userId);
    }

    @PostMapping("/{userId}/scripts/transfer")
    @Transactional
    public Map<String, Object> transferScripts(@PathVariable String userId,
                                                @RequestBody Map<String, String> body,
                                                HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        int count = lifecycleService.transferScripts(workspaceId, userId,
                body == null ? null : body.get("targetUserId"));
        auditService.recordCurrent(workspaceId, "USER_SCRIPTS_TRANSFER",
                null, userId, true, null);
        return Map.of("success", true, "count", count);
    }

    @DeleteMapping("/{userId}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String userId, HttpSession session) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        lifecycleService.softDelete(userId, securityUtil.currentUserId());
        auditService.recordCurrent(workspaceId, "USER_SOFT_DELETE",
                null, userId, true, null);
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
