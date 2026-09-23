package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.repository.AuditLogRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 创建用户须同时保存账号、工作空间角色和审计记录。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:lyradb-user-create;DB_CLOSE_DELAY=-1;MODE=MySQL",
                "jasypt.encryptor.password=integration-test-only-master-key",
                "app.edition=enterprise",
                "app.enterprise.bootstrap-admin-username=testadmin",
                "app.enterprise.bootstrap-admin-password=Bootstrap#Pass1234",
                "app.driver-cache-dir=target/test-driver-cache"
        })
@ActiveProfiles("dev")
class AdminUserCreationIntegrationTest {

    @Autowired
    private AdminUserController controller;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMembershipRepository membershipRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private MockHttpSession session;
    private String workspaceId;

    @BeforeEach
    void signInAsBootstrapAdmin() {
        Workspace workspace = workspaceRepository.findAll().get(0);
        workspaceId = workspace.getId();
        session = new MockHttpSession();
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, workspaceId);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "testadmin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validAnalystIsCreatedInCurrentWorkspace() {
        Map<String, Object> result = controller.create(Map.of(
                "username", "newanalyst",
                "password", "Valid#Pass1234",
                "displayName", "新分析师",
                "roles", List.of("ANALYST")), session);

        User saved = userRepository.findByUsername("newanalyst").orElseThrow();
        assertEquals(saved.getId(), result.get("id"));
        assertEquals(true, result.get("success"));
        assertTrue(membershipRepository.existsByUserIdAndWorkspaceId(saved.getId(), workspaceId));
        assertEquals(Set.of("ANALYST"), userService.workspaceRoles(saved.getId(), workspaceId));
        assertTrue(auditLogRepository.findAll().stream().anyMatch(log ->
                "USER_CREATE".equals(log.getAction())
                        && "newanalyst".equals(log.getGrantedSourceName())));
    }

    @Test
    void weakPasswordReportsValidationErrorWithoutCreatingUser() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.create(Map.of(
                        "username", "weakpassworduser",
                        "password", "WeakPassword123",
                        "roles", List.of("ANALYST")), session));

        assertEquals("密码必须同时包含大写字母、小写字母、数字和特殊字符", error.getMessage());
        assertFalse(userRepository.existsByUsername("weakpassworduser"));
    }

    @Test
    void duplicateUsernameReportsValidationError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.create(Map.of(
                        "username", "testadmin",
                        "password", "Valid#Pass1234",
                        "roles", List.of("ANALYST")), session));

        assertEquals("用户名已存在: testadmin", error.getMessage());
    }
}
