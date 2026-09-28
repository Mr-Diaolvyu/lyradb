package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AdminUserLifecycleService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserRoleControllerTest {
    @Test
    void roleUpdateUsesCurrentWorkspaceAndAuditsTheChange() {
        UserService users = mock(UserService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        AuditService audit = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        when(security.requireCurrentWorkspace(session)).thenReturn("ws");
        when(security.currentUserId()).thenReturn("actor");
        AdminUserController controller = controller(users, security, audit);

        Map<String, Object> result = controller.updateRoles("analyst",
                Map.of("roles", List.of("DS_ADMIN")), session);

        assertThat(result).containsEntry("success", true);
        verify(security).requireRole("PLATFORM_ADMIN");
        verify(users).updateRoles("analyst", "ws", List.of("DS_ADMIN"), "actor");
        verify(audit).recordCurrent("ws", "USER_ROLES_UPDATE",
                null, "analyst", true, null);
    }

    @Test
    void nonAdminCannotSubmitRoleChange() {
        UserService users = mock(UserService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        AuditService audit = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        doThrow(new AccessDeniedException("forbidden"))
                .when(security).requireRole("PLATFORM_ADMIN");

        assertThatThrownBy(() -> controller(users, security, audit)
                .updateRoles("analyst", Map.of("roles", List.of("DS_ADMIN")), session))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(users, audit);
    }

    private static AdminUserController controller(UserService users,
            SecurityUtil security, AuditService audit) {
        return new AdminUserController(users, security, audit,
                mock(AdminUserLifecycleService.class));
    }
}
