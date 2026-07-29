package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录成功必须轮换既有 Session，并绑定凭据版本与当前 Workspace。
 */
class AuthControllerSessionSecurityTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulLoginRotatesExistingSessionAndBindsSecurityScope() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        UserRepository userRepository = mock(UserRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        UserService userService = mock(UserService.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        AuditService auditService = mock(AuditService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(approvalService.requiredApproverRole("workspace-1")).thenReturn("STEWARD");
        AuthController controller = new AuthController(
                authenticationManager, userRepository, workspaceRepository,
                userService, securityUtil, auditService, approvalService);

        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        workspace.setName("主空间");
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setCredentialVersion(7L);
        user.getRoles().add("ANALYST");
        user.getWorkspaces().add(workspace);
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "N/A", List.of()));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(securityUtil.requireCurrentWorkspace(any())).thenReturn("workspace-1");
        when(securityUtil.effectiveRoles("workspace-1")).thenReturn(Set.of("ANALYST"));
        when(securityUtil.accessibleWorkspaceIds()).thenReturn(Set.of("workspace-1"));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        MockHttpServletRequest request = new MockHttpServletRequest();
        String originalSessionId = request.getSession(true).getId();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, Object> result = controller.login(
                Map.of("username", "alice", "password", "correct-password"),
                request, response);

        assertNotEquals(originalSessionId, request.getSession(false).getId());
        assertEquals(7L, request.getSession(false)
                .getAttribute(SecurityUtil.CREDENTIAL_VERSION));
        assertEquals("workspace-1", request.getSession(false)
                .getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID));
        assertEquals("workspace-1", result.get("currentWorkspaceId"));
        verify(auditService).record(
                "workspace-1", "user-1", "alice", "ANALYST",
                null, null, null, "LOGIN", "LOGIN_SUCCESS", null,
                0, 0, 0, true, null, null);
    }
    @Test
    void auditFailureInvalidatesNewlyAuthenticatedSession() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        UserRepository userRepository = mock(UserRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        AuditService auditService = mock(AuditService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(approvalService.requiredApproverRole("workspace-1")).thenReturn("STEWARD");
        AuthController controller = new AuthController(
                authenticationManager, userRepository, workspaceRepository,
                mock(UserService.class), securityUtil, auditService, approvalService);

        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        user.setCredentialVersion(3L);
        user.getRoles().add("ANALYST");
        user.getWorkspaces().add(workspace);
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "N/A", List.of()));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(securityUtil.accessibleWorkspaceIds())
                .thenReturn(Set.of("workspace-1"));
        when(securityUtil.effectiveRoles("workspace-1")).thenReturn(Set.of("ANALYST"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).record(
                        "workspace-1", "user-1", "alice", "ANALYST",
                        null, null, null, "LOGIN", "LOGIN_SUCCESS", null,
                        0, 0, 0, true, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) request.getSession(true);

        assertThrows(IllegalStateException.class, () -> controller.login(
                Map.of("username", "alice", "password", "correct-password"),
                request, new MockHttpServletResponse()));

        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

}
