package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDataSourceControllerTest {

    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AuditService auditService;
    @Mock
    private HttpSession session;

    @Test
    void revealCredentialRequiresWorkspaceAdminAndWritesAudit() {
        DataSource source = source();
        when(dataSourceService.getEntity("source-1"))
                .thenReturn(source);
        when(dataSourceService.getPlaintextCredential(
                "source-1", "password"))
                .thenReturn("db-secret");

        ResponseEntity<Map<String, Object>> response = controller()
                .revealCredential("source-1",
                        Map.of("field", "password"), session);

        verify(securityUtil).requireRole("DS_ADMIN");
        verify(securityUtil).requireResourceInWorkspace(
                "workspace-1", session);
        verify(auditService).recordCurrent(
                "workspace-1", "DATA_SOURCE_CREDENTIAL_REVEAL",
                "source-1", "生产库", true, null);
        assertThat(response.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("field", "password", "value", "db-secret"));
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store, private");
        assertThat(response.getHeaders().getFirst("Pragma"))
                .isEqualTo("no-cache");
    }

    @Test
    void revealCredentialRejectsUserWithoutAdminRoleBeforeReadingSource() {
        doThrow(new AccessDeniedException("forbidden"))
                .when(securityUtil).requireRole("DS_ADMIN");

        assertThatThrownBy(() -> controller().revealCredential(
                "source-1", Map.of("field", "password"), session))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(dataSourceService, auditService);
    }

    private AdminDataSourceController controller() {
        return new AdminDataSourceController(
                dataSourceService, securityUtil, auditService);
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDisplayName("生产库");
        return source;
    }
}
