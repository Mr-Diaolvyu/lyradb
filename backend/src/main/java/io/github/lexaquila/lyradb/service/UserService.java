package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.model.entity.WorkspaceMembership;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 企业用户、密码与工作空间成员关系服务。
 */
@Service
public class UserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");
    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PLATFORM_ADMIN", "DS_ADMIN", "STEWARD", "ANALYST", "AUDITOR");
    private static final Set<String> WORKSPACE_ROLES = Set.of(
            "DS_ADMIN", "STEWARD", "ANALYST", "AUDITOR");

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                       WorkspaceMembershipRepository membershipRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> listAll() {
        return userRepository.findAll();
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
    }

    public User getById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
    }

    @Transactional
    public User create(String username, String password, String displayName, String email,
                       List<String> roles) {
        String normalizedUsername = normalizeUsername(username);
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new RuntimeException("用户名已存在: " + normalizedUsername);
        }
        validatePassword(normalizedUsername, password);
        List<String> normalizedRoles = normalizeRoles(roles);

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank()
                ? normalizedUsername : displayName.trim());
        user.setEmail(email == null || email.isBlank() ? null : email.trim());
        user.setEnabled(true);
        /*
         * User.roles 仅作为平台角色与旧数据兼容载体；工作空间授权以
         * WorkspaceMembership 为唯一判定来源。
         */
        user.setRoles(normalizedRoles);
        return userRepository.save(user);
    }

    @Transactional
    public void setPassword(String username, String newPassword) {
        User user = getByUsername(username);
        validatePassword(user.getUsername(), newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = getByUsername(username);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("当前密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new RuntimeException("新密码不能与当前密码相同");
        }
        validatePassword(user.getUsername(), newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        userRepository.save(user);
    }

    @Transactional
    public void assignWorkspace(String username, String workspaceId) {
        User user = getByUsername(username);
        List<String> inherited = user.getRoles().stream()
                .filter(WORKSPACE_ROLES::contains)
                .toList();
        assignWorkspace(username, workspaceId, inherited.isEmpty() ? List.of("ANALYST") : inherited);
    }

    @Transactional
    public void assignWorkspace(String username, String workspaceId, List<String> workspaceRoles) {
        User user = getByUsername(username);
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作空间不存在: " + workspaceId));
        if (user.getWorkspaces().stream().noneMatch(item -> item.getId().equals(workspaceId))) {
            user.getWorkspaces().add(workspace);
            userRepository.save(user);
        }
        List<String> normalized = normalizeWorkspaceRoles(workspaceRoles);
        WorkspaceMembership membership = membershipRepository
                .findByUserIdAndWorkspaceId(user.getId(), workspaceId)
                .orElseGet(WorkspaceMembership::new);
        membership.setUserId(user.getId());
        membership.setWorkspaceId(workspaceId);
        membership.setRolesCsv(String.join(",", normalized));
        membershipRepository.save(membership);
    }

    public boolean belongsToWorkspace(String userId, String workspaceId) {
        return userId != null && workspaceId != null
                && membershipRepository.existsByUserIdAndWorkspaceId(userId, workspaceId);
    }

    public Set<String> workspaceRoles(String userId, String workspaceId) {
        return membershipRepository.findByUserIdAndWorkspaceId(userId, workspaceId)
                .map(membership -> parseWorkspaceRolesCsv(membership.getRolesCsv()))
                .orElseGet(Set::of);
    }

    public Set<String> workspaceIds(String userId) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        membershipRepository.findByUserId(userId)
                .forEach(membership -> result.add(membership.getWorkspaceId()));
        return result;
    }

    static Set<String> parseWorkspaceRolesCsv(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String rawRole : rolesCsv.split(",")) {
            String role = rawRole.trim().toUpperCase(Locale.ROOT);
            if (role.isEmpty()) {
                continue;
            }
            if (!WORKSPACE_ROLES.contains(role)) {
                throw new IllegalStateException("工作空间角色配置无效");
            }
            result.add(role);
        }
        return result;
    }

    /**
     * 升级旧库时，把旧 User.roles 按其原有 workspace 关系一次性迁入作用域角色表。
     */
    @Transactional
    public int ensureLegacyMemberships() {
        int created = 0;
        for (User user : userRepository.findAll()) {
            List<String> roles = user.getRoles().stream()
                    .filter(WORKSPACE_ROLES::contains)
                    .toList();
            if (roles.isEmpty()) {
                roles = List.of("ANALYST");
            }
            for (Workspace workspace : user.getWorkspaces()) {
                if (!membershipRepository.existsByUserIdAndWorkspaceId(user.getId(), workspace.getId())) {
                    WorkspaceMembership membership = new WorkspaceMembership();
                    membership.setUserId(user.getId());
                    membership.setWorkspaceId(workspace.getId());
                    membership.setRolesCsv(String.join(",", roles));
                    membershipRepository.save(membership);
                    created++;
                }
            }
        }
        return created;
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            throw new RuntimeException("用户名须为 3-64 位字母、数字、点、下划线或连字符");
        }
        return value;
    }

    private static List<String> normalizeRoles(List<String> roles) {
        List<String> source = roles == null || roles.isEmpty() ? List.of("ANALYST") : roles;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String role : source) {
            String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_ROLES.contains(normalized)) {
                throw new RuntimeException("不支持的角色: " + role);
            }
            result.add(normalized);
        }
        return new ArrayList<>(result);
    }

    private static List<String> normalizeWorkspaceRoles(List<String> roles) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
                if ("PLATFORM_ADMIN".equals(normalized)) {
                    continue;
                }
                if (!WORKSPACE_ROLES.contains(normalized)) {
                    throw new RuntimeException("不支持的工作空间角色: " + role);
                }
                result.add(normalized);
            }
        }
        if (result.isEmpty()) {
            result.add("ANALYST");
        }
        return new ArrayList<>(result);
    }

    public static void validatePassword(String username, String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new RuntimeException("密码长度必须为 12-128 位");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean special = password.chars().anyMatch(character -> !Character.isLetterOrDigit(character));
        if (!(upper && lower && digit && special)) {
            throw new RuntimeException("密码必须同时包含大写字母、小写字母、数字和特殊字符");
        }
        if (username != null && password.toLowerCase(Locale.ROOT)
                .contains(username.toLowerCase(Locale.ROOT))) {
            throw new RuntimeException("密码不能包含用户名");
        }
    }
}
