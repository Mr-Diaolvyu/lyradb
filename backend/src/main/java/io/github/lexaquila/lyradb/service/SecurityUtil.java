package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户工具（企业版）
 */
@Component
public class SecurityUtil {

    private final UserRepository userRepository;

    public SecurityUtil(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Authentication auth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public String currentUsername() {
        Authentication a = auth();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal())) {
            return null;
        }
        return a.getName();
    }

    public User currentUser() {
        String u = currentUsername();
        if (u == null) return null;
        return userRepository.findByUsername(u).orElse(null);
    }

    public String currentUserId() {
        User u = currentUser();
        return u != null ? u.getId() : null;
    }

    public boolean hasRole(String role) {
        Authentication a = auth();
        if (a == null) return false;
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(r -> r.equals("ROLE_" + role));
    }

    public void requireRole(String role) {
        if (!hasRole(role) && !hasRole("PLATFORM_ADMIN")) {
            throw new RuntimeException("无权限，需要角色: " + role);
        }
    }
}
