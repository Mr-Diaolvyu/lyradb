package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理（PLATFORM_ADMIN）
 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserService userService;
    private final SecurityUtil securityUtil;

    public AdminUserController(UserService userService, SecurityUtil securityUtil) {
        this.userService = userService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        securityUtil.requireRole("PLATFORM_ADMIN");
        return userService.listAll().stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("email", u.getEmail());
            m.put("enabled", u.isEnabled());
            m.put("roles", u.getRoles());
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        securityUtil.requireRole("PLATFORM_ADMIN");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");
        String id = userService.create(
                (String) body.get("username"),
                (String) body.get("password"),
                (String) body.get("displayName"),
                (String) body.get("email"),
                roles
        ).getId();
        return Map.of("id", id, "success", true);
    }
}
