package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.EnterpriseMetadataSnapshotService;
import io.github.lexaquila.lyradb.service.MetadataSnapshotSessionStore;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnterpriseMetadataSnapshotControllerTest {

    @Mock
    private EnterpriseMetadataSnapshotService snapshotService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AuditService auditService;
    @Mock
    private HttpSession session;

    @Test
    void captureAuditUsesServerResolvedDataSourceId() {
        User owner = new User();
        owner.setId("user-1");
        EnterpriseMetadataSnapshotService.CaptureRequest request =
                new EnterpriseMetadataSnapshotService.CaptureRequest(
                        "sales-source", "warehouse",
                        List.of("sales"), List.of("sales.orders"));
        EnterpriseMetadataSnapshotService.CaptureResult result =
                new EnterpriseMetadataSnapshotService.CaptureResult(
                        "snapshot-1", "source-1", "sales-source",
                        "warehouse", List.of("sales"),
                        List.of("sales.orders"), 1, 1, 1, 2, 50,
                        List.of(), "a".repeat(64),
                        LocalDateTime.now().plusMinutes(30));
        when(securityUtil.requireCurrentWorkspace(session))
                .thenReturn("workspace-1");
        when(securityUtil.requireCurrentUser()).thenReturn(owner);
        when(snapshotService.capture("workspace-1", owner, request))
                .thenReturn(result);
        EnterpriseMetadataSnapshotController controller =
                new EnterpriseMetadataSnapshotController(
                        snapshotService, securityUtil, auditService);

        EnterpriseMetadataSnapshotService.CaptureResult actual =
                controller.capture(request, session);

        assertSame(result, actual);
        verify(auditService).recordCurrentMetadata(
                "workspace-1", "AI_METADATA_SNAPSHOT_CREATE",
                "source-1", "sales-source", "snapshot-1",
                new MetadataSnapshotSessionStore.MapScope(
                        "warehouse", List.of("sales"),
                        List.of("sales.orders")),
                "a".repeat(64));
    }
}
