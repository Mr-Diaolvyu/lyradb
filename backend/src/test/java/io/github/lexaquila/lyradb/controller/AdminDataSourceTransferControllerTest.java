package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceTransferApprovalService;
import io.github.lexaquila.lyradb.service.EnterpriseConnectionTransferService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDataSourceTransferControllerTest {

    @Mock
    private DataSourceTransferApprovalService transferApprovalService;
    @Mock
    private EnterpriseConnectionTransferService transferService;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AuditService auditService;
    @Mock
    private HttpSession session;

    @Test
    void templateDownloadRequiresAdminAndWritesAudit() throws Exception {
        byte[] content = "xlsx".getBytes(StandardCharsets.UTF_8);
        when(securityUtil.requireCurrentWorkspace(session))
                .thenReturn("workspace-1");
        when(transferService.createImportTemplate()).thenReturn(
                new EnterpriseConnectionTransferService.ExportFile(
                        content,
                        "LyraDB-连接导入模板.xlsx",
                        "application/vnd.openxmlformats-officedocument"
                                + ".spreadsheetml.sheet"));
        AdminDataSourceTransferController controller = controller();

        ResponseEntity<byte[]> response =
                controller.downloadImportTemplate(session);

        verify(securityUtil).requireRole("DS_ADMIN");
        verify(securityUtil).requireCurrentWorkspace(session);
        verify(auditService).recordCurrent(
                "workspace-1",
                "DATA_SOURCE_IMPORT_TEMPLATE_DOWNLOAD",
                null, null, true, null);
        assertThat(response.getBody()).isSameAs(content);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store, private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentDisposition()
                .getFilename()).isEqualTo("LyraDB-连接导入模板.xlsx");
    }

    private AdminDataSourceTransferController controller() {
        return new AdminDataSourceTransferController(
                transferApprovalService, transferService, approvalService,
                securityUtil, auditService);
    }
}
