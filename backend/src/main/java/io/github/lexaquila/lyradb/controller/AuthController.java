package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 企业版认证、改密及工作空间会话控制器。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final int MAX_FAILURES = 5;
    private static final int MAX_ATTEMPT_ENTRIES = 10_000;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;
    private static final long ENTRY_TTL_MILLIS = 60 * 60 * 1000L;

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;
    private final ApprovalService approvalService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository,
                          WorkspaceRepository workspaceRepository, UserService userService,
                          SecurityUtil securityUtil, AuditService auditService,
                          ApprovalService approvalService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
        this.approvalService = approvalService;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body,
                                     HttpServletRequest request, HttpServletResponse response) {
        String username = trimToNull(body.get("username"));
        String password = body.get("password");
        if (username == null || password == null) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        String attemptKey = username.toLowerCase(Locale.ROOT) + "|" + request.getRemoteAddr();
        long now = System.currentTimeMillis();
        if (isLocked(attemptKey, now)) {
            auditLoginFailureSafely(username, "登录尝试过多");
            throw new BadCredentialsException("用户名或密码错误");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException exception) {
            recordFailure(attemptKey, now);
            auditLoginFailureSafely(username, "认证失败");
            throw new BadCredentialsException("用户名或密码错误");
        }
        loginAttempts.remove(attemptKey);

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        HttpSession session = request.getSession(true);
        session.setAttribute(SecurityUtil.CREDENTIAL_VERSION, user.getCredentialVersion());
        String workspaceId = securityUtil.accessibleWorkspaceIds().stream()
                .findFirst().orElse(null);
        if (workspaceId == null) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            throw new org.springframework.security.access.AccessDeniedException(
                    "当前用户未加入任何工作空间");
        }
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, workspaceId);
        try {
            auditService.record(
                    workspaceId, user.getId(), user.getUsername(), primaryRole(workspaceId),
                    null, null, null, "LOGIN", "LOGIN_SUCCESS", null,
                    0, 0, 0, true, null, null);
        } catch (RuntimeException auditFailure) {
            // 企业登录必须留下审计证据；审计不可用时销毁刚建立的认证会话。
            session.invalidate();
            SecurityContextHolder.clearContext();
            throw auditFailure;
        }
        log.info("用户登录成功: {}", username);
        return userInfo(user, session);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        User user = securityUtil.requireCurrentUser();
        securityUtil.requireCurrentWorkspace(session);
        return userInfo(user, session);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        User user = securityUtil.currentUser();
        HttpSession session = request.getSession(false);
        String workspaceId = session == null ? null
                : valueOf(session.getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID));
        RuntimeException auditFailure = null;
        try {
            if (user != null) {
                auditService.record(
                        workspaceId, user.getId(), user.getUsername(), primaryRole(workspaceId),
                        null, null, null, "LOGOUT", "LOGOUT", null,
                        0, 0, 0, true, null, null);
            }
        } catch (RuntimeException exception) {
            auditFailure = exception;
        } finally {
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        if (auditFailure != null) {
            // 审计故障需要显式暴露，但绝不能因此保留认证会话。
            throw auditFailure;
        }
        return Map.of("success", true);
    }

    @PostMapping("/workspace")
    public Map<String, Object> switchWorkspace(@RequestBody Map<String, String> body, HttpSession session) {
        String workspaceId = trimToNull(body.get("workspaceId"));
        Object previousWorkspace = session.getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID);
        securityUtil.switchWorkspace(session, workspaceId);
        try {
            auditService.recordCurrent(workspaceId, "WORKSPACE_SWITCH", null, null, true, null);
        } catch (RuntimeException auditFailure) {
            if (previousWorkspace == null) {
                session.removeAttribute(SecurityUtil.CURRENT_WORKSPACE_ID);
            } else {
                session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, previousWorkspace);
            }
            throw auditFailure;
        }
        return Map.of("success", true, "currentWorkspaceId", workspaceId);
    }

    @PostMapping("/password")
    @Transactional
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        User user = securityUtil.requireCurrentUser();
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        userService.changePassword(
                user.getUsername(), body.get("currentPassword"), body.get("newPassword"));
        try {
            auditService.recordJoined(
                    workspaceId, user.getId(), user.getUsername(), primaryRole(workspaceId),
                    null, null, null, "PASSWORD", "PASSWORD_CHANGE", null,
                    0, 0, 0, true, null, null);
        } finally {
            try {
                session.invalidate();
            } finally {
                SecurityContextHolder.clearContext();
                expireCookie(response, "JSESSIONID",
                        request.getContextPath().isBlank() ? "/" : request.getContextPath(),
                        request.isSecure(), true);
                expireCookie(response, "XSRF-TOKEN", "/", request.isSecure(), false);
            }
        }
        return Map.of("success", true, "reauthenticationRequired", true);
    }

    private Map<String, Object> userInfo(User user, HttpSession session) {
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        Map<String, Object> info = new HashMap<>();
        info.put("username", user.getUsername());
        info.put("displayName", user.getDisplayName());
        info.put("roles", securityUtil.effectiveRoles(workspaceId).stream()
                .map(role -> "ROLE_" + role)
                .collect(Collectors.toList()));
        String effectiveApproverRole = approvalService.requiredApproverRole(workspaceId);
        info.put("effectiveApproverRole", effectiveApproverRole);
        info.put("canApprove", securityUtil.hasRole(effectiveApproverRole));
        info.put("canViewWorkspaceAudit",
                securityUtil.hasRole("DS_ADMIN")
                        || securityUtil.hasRole("STEWARD")
                        || securityUtil.hasRole("AUDITOR"));

        Set<String> accessibleIds = securityUtil.accessibleWorkspaceIds();
        List<Map<String, Object>> workspaces = workspaceRepository.findAll().stream()
                .filter(workspace -> accessibleIds.contains(workspace.getId()))
                .map(workspace -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", workspace.getId());
                    item.put("name", workspace.getName());
                    return item;
                })
                .collect(Collectors.toList());
        info.put("workspaces", workspaces);
        info.put("currentWorkspaceId", workspaceId);
        return info;
    }

    private boolean isLocked(String key, long now) {
        cleanupAttempts(now);
        LoginAttempt attempt = loginAttempts.get(key);
        return attempt != null && attempt.lockedUntil > now;
    }

    private void recordFailure(String key, long now) {
        cleanupAttempts(now);
        loginAttempts.compute(key, (ignored, existing) -> {
            LoginAttempt attempt = existing == null ? new LoginAttempt() : existing;
            if (now - attempt.lastAttempt > ENTRY_TTL_MILLIS) {
                attempt.failures = 0;
                attempt.lockedUntil = 0;
            }
            attempt.failures++;
            attempt.lastAttempt = now;
            if (attempt.failures >= MAX_FAILURES) {
                attempt.lockedUntil = now + LOCK_MILLIS;
            }
            return attempt;
        });
    }

    private void cleanupAttempts(long now) {
        loginAttempts.entrySet().removeIf(entry ->
                now - entry.getValue().lastAttempt > ENTRY_TTL_MILLIS
                        && entry.getValue().lockedUntil <= now);
        if (loginAttempts.size() >= MAX_ATTEMPT_ENTRIES) {
            loginAttempts.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAttempt))
                    .ifPresent(entry -> loginAttempts.remove(entry.getKey(), entry.getValue()));
        }
    }

    private void auditLoginFailureSafely(String username, String reason) {
        try {
            auditService.record(null, null, username, null,
                    null, null, null, "LOGIN", "LOGIN_FAILURE", null,
                    0, 0, 0, false, reason, null);
        } catch (RuntimeException auditFailure) {
            log.error("登录失败审计写入失败: {}", auditFailure.getMessage());
        }
    }

    private String primaryRole(String workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        return securityUtil.effectiveRoles(workspaceId).stream().findFirst().orElse("ANALYST");
    }

    private static void expireCookie(HttpServletResponse response, String name, String path,
                                     boolean secure, boolean httpOnly) {
        String cookie = name + "=; Path=" + path + "; Max-Age=0; SameSite=Lax"
                + (secure ? "; Secure" : "") + (httpOnly ? "; HttpOnly" : "");
        response.addHeader("Set-Cookie", cookie);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String valueOf(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class LoginAttempt {
        private int failures;
        private long lockedUntil;
        private long lastAttempt;
    }
}
