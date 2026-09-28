package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.model.entity.WorkspaceMembership;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceRoleUpdateTest {
    private UserRepository users;
    private WorkspaceRepository workspaces;
    private WorkspaceMembershipRepository memberships;
    private UserService service;
    private User target;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        memberships = mock(WorkspaceMembershipRepository.class);
        service = new UserService(users, workspaces, memberships,
                mock(PasswordEncoder.class));
        target = new User();
        target.setId("target");
        target.setUsername("analyst");
        target.setRoles(List.of("ANALYST"));
        Workspace workspace = new Workspace();
        workspace.setId("ws");
        target.getWorkspaces().add(workspace);
        when(users.findByUsername("analyst")).thenReturn(Optional.of(target));
        when(workspaces.findById("ws")).thenReturn(Optional.of(workspace));
        when(memberships.existsByUserIdAndWorkspaceId("target", "ws"))
                .thenReturn(true);
    }

    @Test
    void updatesCurrentWorkspaceRolesAndInvalidatesExistingSessions() {
        WorkspaceMembership membership = new WorkspaceMembership();
        when(memberships.findByUserIdAndWorkspaceId("target", "ws"))
                .thenReturn(Optional.of(membership));

        service.updateRoles("analyst", "ws",
                List.of("PLATFORM_ADMIN", "DS_ADMIN"), "actor");

        assertThat(target.getRoles()).containsExactly("ANALYST", "PLATFORM_ADMIN");
        assertThat(target.getCredentialVersion()).isEqualTo(1);
        assertThat(membership.getRolesCsv()).isEqualTo("DS_ADMIN");
        verify(users).save(target);
        verify(memberships).save(membership);
    }

    @Test
    void refusesRemovingTheLastActivePlatformAdmin() {
        target.setRoles(List.of("PLATFORM_ADMIN", "ANALYST"));
        when(users.findAll()).thenReturn(List.of(target));

        assertThatThrownBy(() -> service.updateRoles("analyst", "ws",
                List.of("ANALYST"), "actor"))
                .hasMessageContaining("最后一个启用的平台管理员");
        assertThat(target.getCredentialVersion()).isZero();
        verify(users, never()).save(any());
    }
}
