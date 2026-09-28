package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.service.AdminGrantScopeService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminGrantScopeControllerTest {
    @Test
    void checksRoleAndWorkspaceBeforeReadingMetadata() throws Exception {
        AdminGrantScopeService scopes = mock(AdminGrantScopeService.class);
        DataSourceService sources = mock(DataSourceService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        HttpSession session = mock(HttpSession.class);
        DataSource source = new DataSource();
        source.setWorkspaceId("workspace-1");
        when(sources.getEntity("source-1")).thenReturn(source);
        when(scopes.options(source)).thenReturn(
                new AdminGrantScopeService.ScopeOptions(List.of(), List.of(), false));

        assertThat(new AdminGrantScopeController(scopes, sources, security)
                .options("source-1", session).tables()).isEmpty();

        verify(security).requireRole("DS_ADMIN");
        verify(security).requireResourceInWorkspace("workspace-1", session);
        verify(scopes).options(source);
    }

    @Test
    void rejectsOtherWorkspaceBeforeReadingMetadata() {
        AdminGrantScopeService scopes = mock(AdminGrantScopeService.class);
        DataSourceService sources = mock(DataSourceService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        HttpSession session = mock(HttpSession.class);
        DataSource source = new DataSource();
        source.setWorkspaceId("other-workspace");
        when(sources.getEntity("source-1")).thenReturn(source);
        doThrow(new AccessDeniedException("forbidden")).when(security)
                .requireResourceInWorkspace("other-workspace", session);

        assertThatThrownBy(() -> new AdminGrantScopeController(scopes, sources,
                security).options("source-1", session))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(scopes);
    }
}
