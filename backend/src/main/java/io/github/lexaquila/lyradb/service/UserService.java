package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理服务（企业版）
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> listAll() {
        return userRepository.findAll();
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
    }

    public User create(String username, String password, String displayName, String email, List<String> roles) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName != null ? displayName : username);
        user.setEmail(email);
        user.setEnabled(true);
        user.setRoles(roles != null ? roles : new ArrayList<>(List.of("ANALYST")));
        return userRepository.save(user);
    }

    public void setPassword(String username, String newPassword) {
        User user = getByUsername(username);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void assignWorkspace(String username, String workspaceId) {
        User user = getByUsername(username);
        Workspace ws = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作空间不存在: " + workspaceId));
        if (user.getWorkspaces().stream().noneMatch(w -> w.getId().equals(workspaceId))) {
            user.getWorkspaces().add(ws);
            userRepository.save(user);
        }
    }
}
