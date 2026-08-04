

package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前登录用户、平台角色及工作空间作用域角色工具。
 */
@Component
public class SecurityUtil {

    public static final String CURRENT_WORKSPACE_ID = "currentWorkspaceId";
    public static final String CREDENTIAL_VERSION = "credentialVersion";
    public static final String REQUEST_WORKSPACE_SNAPSHOT =
            SecurityUtil.class.getName() + ".WORKSPACE_SNAPSHOT";
    private static final Set<String> WORKSPACE_ROLES = Set.of(
            "DS_ADMIN", "STEWARD", "ANALYST", "AUDITOR");

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public SecurityUtil(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                        WorkspaceMembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
    }

    public Authentication auth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public String currentUsername() {
        Authentication authentication = auth();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication.getName();
    }

    public User currentUser() {
        String username = currentUsername();
        return username == null ? null : userRepository.findByUsername(username).orElse(null);
    }

    public User requireCurrentUser() {
        User user = currentUser();
        if (user == null || !user.isEnabled()) {
            throw new AuthenticationCredentialsNotFoundException("未登录或账号已停用");
        }
        return user;
    }

    public String currentUserId() {
        User user = currentUser();
        return user == null ? null : user.getId();
    }

    public boolean isPlatformAdmin() {
        User user = currentUser();
        return user != null && user.getRoles().contains("PLATFORM_ADMIN");
    }

    /**
     * PLATFORM_ADMIN 为平台级；其他角色只在当前会话工作空间内判定。
     */
    public boolean hasRole(String role) {
        if ("PLATFORM_ADMIN".equals(role)) {
            return isPlatformAdmin();
        }
        if (isPlatformAdmin()) {
            return true;
        }
        HttpSession session = currentSession();
        if (session == null) {
            return false;
        }
        try {
            String workspaceId = requireCurrentWorkspace(session);
            return workspaceRoles(requireCurrentUser().getId(), workspaceId).contains(role);
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    public void requireRole(String role) {
        if (!hasRole(role)) {
            throw new AccessDeniedException("当前工作空间无此角色: " + role);
        }
    }

    public Set<String> effectiveRoles(String workspaceId) {
        User user = requireCurrentUser();
        Set<String> result = new LinkedHashSet<>();
        if (user.getRoles().contains("PLATFORM_ADMIN")) {
            result.add("PLATFORM_ADMIN");
        }
        result.addAll(workspaceRoles(user.getId(), workspaceId));
        return result;
    }

    public void requireWorkspaceAccess(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new AccessDeniedException("未选择工作空间");
        }
        User user = requireCurrentUser();
        if (isPlatformAdmin()) {
            if (!workspaceRepository.existsById(workspaceId)) {
                throw new AccessDeniedException("工作空间不存在");
            }
            return;
        }
        if (!membershipRepository.existsByUserIdAndWorkspaceId(user.getId(), workspaceId)) {
            throw new AccessDeniedException("无权访问该工作空间");
        }
    }

    public Set<String> accessibleWorkspaceIds() {
        User user = requireCurrentUser();
        Set<String> ids = new LinkedHashSet<>();
        if (isPlatformAdmin()) {
            workspaceRepository.findAll().forEach(workspace -> ids.add(workspace.getId()));
        } else {
            membershipRepository.findByUserId(user.getId())
                    .forEach(membership -> ids.add(membership.getWorkspaceId()));
        }
        return ids;
    }

    public String requireCurrentWorkspace(HttpSession session) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            Object snapshot = request.getAttribute(REQUEST_WORKSPACE_SNAPSHOT);
            if (snapshot instanceof String workspaceId && !workspaceId.isBlank()) {
                requireWorkspaceAccess(workspaceId);
                return workspaceId;
            }
        }
        Object current = session.getAttribute(CURRENT_WORKSPACE_ID);
        String workspaceId = current == null ? null : current.toString();
        if (workspaceId == null || workspaceId.isBlank()) {
            workspaceId = accessibleWorkspaceIds().stream().findFirst().orElse(null);
            if (workspaceId == null) {
                throw new AccessDeniedException("当前用户未加入任何工作空间");
            }
            session.setAttribute(CURRENT_WORKSPACE_ID, workspaceId);
        }
        requireWorkspaceAccess(workspaceId);
        if (request != null) {
            request.setAttribute(REQUEST_WORKSPACE_SNAPSHOT, workspaceId);
        }
        return workspaceId;
    }

    public String requireCurrentWorkspace() {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            Object snapshot = request.getAttribute(
                    REQUEST_WORKSPACE_SNAPSHOT);
            if (snapshot instanceof String workspaceId
                    && !workspaceId.isBlank()) {
                requireWorkspaceAccess(workspaceId);
                return workspaceId;
            }
        }
        HttpSession session = currentSession();
        if (session == null) {
            throw new AuthenticationCredentialsNotFoundException("会话不存在");
        }
        return requireCurrentWorkspace(session);
    }

    public void switchWorkspace(HttpSession session, String workspaceId) {
        requireWorkspaceAccess(workspaceId);
        session.setAttribute(CURRENT_WORKSPACE_ID, workspaceId);
    }

    public void requireResourceInWorkspace(String resourceWorkspaceId, HttpSession session) {
        String current = requireCurrentWorkspace(session);
        if (!current.equals(resourceWorkspaceId)) {
            throw new AccessDeniedException("资源不属于当前工作空间");
        }
    }

    private Set<String> workspaceRoles(String userId, String workspaceId) {
        return membershipRepository.findByUserIdAndWorkspaceId(userId, workspaceId)
                .map(membership -> parseWorkspaceRoles(membership.getRolesCsv()))
                .orElseGet(Set::of);
    }

    private static Set<String> parseWorkspaceRoles(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String value : rolesCsv.split(",")) {
            String role = value.trim().toUpperCase(java.util.Locale.ROOT);
            if (role.isEmpty()) {
                continue;
            }
            if (!WORKSPACE_ROLES.contains(role)) {
                throw new AccessDeniedException("工作空间角色配置无效");
            }
            roles.add(role);
        }
        return roles;
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }

    private static HttpSession currentSession() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getSession(false);
    }
}
