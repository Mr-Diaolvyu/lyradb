package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 认证控制器（企业版会话登录）
 *
 * <p>POST /api/auth/login · GET /api/auth/me · POST /api/auth/logout</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body,
                                     HttpServletRequest request, HttpServletResponse response) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            throw new RuntimeException("用户名/密码不能为空");
        }
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(username, password);
        Authentication auth = authenticationManager.authenticate(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
        securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);

        log.info("用户登录成功: {}", username);
        return userInfo(username, auth);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new RuntimeException("未登录");
        }
        Map<String, Object> info = userInfo(auth.getName(), auth);
        Object cw = session.getAttribute("currentWorkspaceId");
        info.put("currentWorkspaceId", cw == null ? "" : cw.toString());
        return info;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("success", true);
    }

    /** 切换当前工作空间（存会话） */
    @PostMapping("/workspace")
    public Map<String, Object> switchWorkspace(@RequestBody Map<String, String> body, HttpSession session) {
        String workspaceId = body.get("workspaceId");
        session.setAttribute("currentWorkspaceId", workspaceId);
        return Map.of("success", true, "currentWorkspaceId", workspaceId == null ? "" : workspaceId);
    }

    private Map<String, Object> userInfo(String username, Authentication auth) {
        User user = userRepository.findByUsername(username).orElse(null);
        Map<String, Object> info = new HashMap<>();
        info.put("username", username);
        info.put("roles", auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toList()));
        if (user != null) {
            info.put("displayName", user.getDisplayName());
            try {
                List<Map<String, Object>> workspaces = user.getWorkspaces().stream()
                        .map(w -> {
                            Map<String, Object> wm = new HashMap<>();
                            wm.put("id", w.getId());
                            wm.put("name", w.getName());
                            return wm;
                        })
                        .collect(Collectors.toList());
                info.put("workspaces", workspaces);
            } catch (Exception e) {
                // workspaces 为懒加载，可能脱离事务 → 返回空
                info.put("workspaces", List.of());
            }
        }
        return info;
    }
}
