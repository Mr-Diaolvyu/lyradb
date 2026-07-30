package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.AuditLog;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计写入必须失败关闭，且默认不落明文 SQL。
 */
class AuditServiceSecurityTest {

    @Test
    void repositoryFailureIsNotSwallowed() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        doThrow(new RuntimeException("database unavailable"))
                .when(repository).saveAndFlush(any(AuditLog.class));
        AuditService service = new AuditService(
                repository, mock(SecurityUtil.class), new AppProperties(),
                new ObjectMapper());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.record(
                        "workspace-1", "user-1", "alice", "ANALYST",
                        "source-1", "sales", "POSTGRESQL",
                        "QUERY", "QUERY_EXECUTE", "select secret from customer",
                        0, 1, 10, true, null, null));

        assertTrue(exception.getMessage().contains("审计记录失败"));
    }

    @Test
    void defaultAuditStoresHashInsteadOfPlainSqlAndKeepsScope() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditService service = new AuditService(
                repository, mock(SecurityUtil.class), new AppProperties(),
                new ObjectMapper());

        service.record(
                "workspace-1", "user-1", "alice", "ANALYST",
                "source-1", "sales", "POSTGRESQL",
                "QUERY", "QUERY_EXECUTE", "select secret from customer",
                0, 1, 10, true, null, "approval-1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("workspace-1", audit.getWorkspaceId());
        assertEquals("user-1", audit.getUserId());
        assertEquals("source-1", audit.getDataSourceId());
        assertEquals("approval-1", audit.getApprovalRequestId());
        assertNull(audit.getSqlText());
        assertEquals(64, audit.getSqlHash().length());
    }

    @Test
    void failedOperationPersistsBoundedErrorAndResult() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditService service = new AuditService(
                repository, mock(SecurityUtil.class), new AppProperties(),
                new ObjectMapper());
        String oversizedError = "x".repeat(2500);

        service.record(
                "workspace-1", "user-1", "alice", "STEWARD",
                null, null, null,
                "ADMIN", "APPROVAL_REJECT", null,
                0, 0, 5, false, oversizedError, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals(2000, audit.getErrorMessage().length());
        assertEquals(false, audit.getSuccess());
    }
    @Test
    void metadataAuditContainsOnlySnapshotScopeAndDigest()
            throws Exception {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(securityUtil.effectiveRoles("workspace-1"))
                .thenReturn(Set.of("ANALYST"));
        ObjectMapper mapper = new ObjectMapper();
        AuditService service = new AuditService(
                repository, securityUtil, new AppProperties(), mapper);

        service.recordCurrentMetadata(
                "workspace-1", "METADATA_CAPTURE",
                "source-1", "sales-source", "snapshot-1",
                new MetadataSnapshotSessionStore.MapScope(
                        "warehouse", List.of("secret", "sales"),
                        List.of("secret.audit", "sales.orders")),
                "a".repeat(64));

        ArgumentCaptor<AuditLog> captor =
                ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("source-1", audit.getDataSourceId());
        assertNull(audit.getSqlText());
        assertNull(audit.getSqlHash());
        JsonNode details = mapper.readTree(audit.getDetailsJson());
        assertEquals("snapshot-1",
                details.path("snapshotId").asText());
        assertEquals("warehouse",
                details.path("scope").path("database").asText());
        assertEquals("sales",
                details.path("scope").path("schemas").get(0).asText());
        assertEquals("a".repeat(64),
                details.path("contentSha256").asText());
        assertFalse(audit.getDetailsJson().contains("connectionParams"));
        assertFalse(audit.getDetailsJson().contains("password"));
    }
}
