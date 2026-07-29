
package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.model.entity.WorkspaceMembership;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workspace 必须以 Membership 为唯一授权来源，切换失败不能污染会话。
 */
class SecurityUtilWorkspaceTest {

    private UserRepository userRepository;
    private WorkspaceRepository workspaceRepository;
    private WorkspaceMembershipRepository membershipRepository;
    private SecurityUtil securityUtil;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        membershipRepository = mock(WorkspaceMembershipRepository.class);
        securityUtil = new SecurityUtil(
                userRepository, workspaceRepository, membershipRepository);

        user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        user.setEnabled(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "N/A", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void legacyManyToManyEntryDoesNotGrantWorkspaceAccess() {
        Workspace legacyWorkspace = new Workspace();
        legacyWorkspace.setId("workspace-legacy");
        user.getWorkspaces().add(legacyWorkspace);
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-legacy")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> securityUtil.requireWorkspaceAccess("workspace-legacy"));
    }

    @Test
    void failedSwitchDoesNotChangeCurrentWorkspace() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, "workspace-1");
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-2")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> securityUtil.switchWorkspace(session, "workspace-2"));

        assertEquals("workspace-1",
                session.getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID));
    }

    @Test
    void validMembershipAllowsSwitchAndRolesRemainWorkspaceScoped() {
        MockHttpSession session = new MockHttpSession();
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setUserId("user-1");
        membership.setWorkspaceId("workspace-2");
        membership.setRolesCsv("STEWARD,AUDITOR");
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-2")).thenReturn(true);
        when(membershipRepository.findByUserIdAndWorkspaceId(
                "user-1", "workspace-2")).thenReturn(Optional.of(membership));
        when(membershipRepository.findByUserIdAndWorkspaceId(
                "user-1", "workspace-1")).thenReturn(Optional.empty());

        securityUtil.switchWorkspace(session, "workspace-2");

        assertEquals("workspace-2",
                session.getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID));
        assertTrue(securityUtil.effectiveRoles("workspace-2").contains("STEWARD"));
        assertFalse(securityUtil.effectiveRoles("workspace-1").contains("STEWARD"));
    }

    @Test
    void staleSessionWorkspaceIsRejectedInsteadOfSilentlyFallingBack() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, "workspace-stale");
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-stale")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> securityUtil.requireCurrentWorkspace(session));

        verify(membershipRepository).existsByUserIdAndWorkspaceId(
                "user-1", "workspace-stale");
    }

    @Test
    void workspaceRoleRejectsPlatformAdminAndUnknownRolePollution() {
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setUserId("user-1");
        membership.setWorkspaceId("workspace-1");
        when(membershipRepository.findByUserIdAndWorkspaceId(
                "user-1", "workspace-1")).thenReturn(Optional.of(membership));

        membership.setRolesCsv("ANALYST,PLATFORM_ADMIN");
        assertThrows(AccessDeniedException.class,
                () -> securityUtil.effectiveRoles("workspace-1"));

        membership.setRolesCsv("ANALYST,ROOT");
        assertThrows(AccessDeniedException.class,
                () -> securityUtil.effectiveRoles("workspace-1"));
    }

    @Test
    void 同一请求内工作空间快照不受并发会话切换影响() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, "workspace-a");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));

        WorkspaceMembership admin = new WorkspaceMembership();
        admin.setUserId("user-1");
        admin.setWorkspaceId("workspace-a");
        admin.setRolesCsv("DS_ADMIN");
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-a")).thenReturn(true);
        when(membershipRepository.findByUserIdAndWorkspaceId(
                "user-1", "workspace-a")).thenReturn(Optional.of(admin));

        assertTrue(securityUtil.hasRole("DS_ADMIN"));
        session.setAttribute(SecurityUtil.CURRENT_WORKSPACE_ID, "workspace-b");

        assertEquals("workspace-a",
                securityUtil.requireCurrentWorkspace(session));
        assertTrue(securityUtil.hasRole("DS_ADMIN"));
        assertEquals("workspace-a", request.getAttribute(
                SecurityUtil.REQUEST_WORKSPACE_SNAPSHOT));
    }

    @Test
    void platformAdminCanOnlySwitchToExistingWorkspace() {
        user.getRoles().add("PLATFORM_ADMIN");
        MockHttpSession session = new MockHttpSession();
        when(workspaceRepository.existsById("missing")).thenReturn(false);
        when(workspaceRepository.existsById("workspace-1")).thenReturn(true);

        assertThrows(AccessDeniedException.class,
                () -> securityUtil.switchWorkspace(session, "missing"));
        securityUtil.switchWorkspace(session, "workspace-1");

        assertEquals("workspace-1",
                session.getAttribute(SecurityUtil.CURRENT_WORKSPACE_ID));
        verify(membershipRepository, never())
                .existsByUserIdAndWorkspaceId("user-1", "workspace-1");
    }
}
