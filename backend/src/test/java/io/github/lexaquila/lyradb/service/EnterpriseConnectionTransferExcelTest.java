package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionExcelTemplateCodec;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseConnectionTransferExcelTest {

    private DataSourceRepository dataSourceRepository;
    private EnterpriseConnectionTransferService service;

    @BeforeEach
    void setUp() {
        dataSourceRepository = mock(DataSourceRepository.class);
        when(dataSourceRepository.findByWorkspaceIdAndDisplayNameIgnoreCase(
                anyString(), anyString())).thenReturn(List.of());
        DriverRegistry driverRegistry = new DriverRegistry();
        driverRegistry.init();
        service = new EnterpriseConnectionTransferService(
                dataSourceRepository,
                mock(DataSourceService.class),
                mock(DataSourceTransferApprovalService.class),
                mock(ApprovalService.class),
                mock(ApprovalSecurityContextService.class),
                new DataSourceImportPreviewStore(),
                mock(CredentialService.class),
                driverRegistry,
                mock(AuditService.class),
                new ObjectMapper());
    }

    @Test
    void createsAdministratorExcelImportTemplate() throws Exception {
        EnterpriseConnectionTransferService.ExportFile file =
                service.createImportTemplate();

        assertThat(file.fileName())
                .isEqualTo(ConnectionExcelTemplateCodec.FILE_NAME);
        assertThat(file.contentType())
                .isEqualTo(ConnectionExcelTemplateCodec.CONTENT_TYPE);
        assertThat(ConnectionExcelTemplateCodec.hasXlsxSignature(file.content()))
                .isTrue();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getSheet("连接导入")).isNotNull();
            assertThat(workbook.getSheet("字段说明")).isNotNull();
        }
    }

    @Test
    void previewsExcelThroughExistingEnterpriseConflictFlow() throws Exception {
        byte[] filled = filledTemplate();
        User owner = new User();
        owner.setId("owner-1");

        EnterpriseConnectionTransferService.ImportPreview preview =
                service.previewImport("workspace-1", owner, filled, new char[0]);

        assertThat(preview.credentialPolicy())
                .isEqualTo(CredentialExportPolicy.PLAINTEXT.name());
        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertThat(item.displayName()).isEqualTo("Excel PostgreSQL");
            assertThat(item.dbType()).isEqualTo("POSTGRESQL");
            assertThat(item.parameterKeys()).contains("host", "port", "database");
            assertThat(item.credentialKeys()).contains("password");
            assertThat(item.credentialsIncluded()).isTrue();
            assertThat(item.conflict()).isFalse();
        });
    }

    private byte[] filledTemplate() throws Exception {
        byte[] template = service.createImportTemplate().content();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("连接导入");
            Row header = sheet.getRow(1);
            Row row = sheet.createRow(2);
            for (int index = 0; index < header.getLastCellNum(); index++) {
                row.createCell(index);
            }
            set(row, header, "连接名称 *", "Excel PostgreSQL");
            set(row, header, "数据库类型 *", "POSTGRESQL");
            set(row, header, "主机地址", "db.example.com");
            row.getCell(column(header, "端口")).setCellValue(5432);
            set(row, header, "数据库名", "analytics");
            set(row, header, "用户名", "reader");
            set(row, header, "密码（明文）", "secret");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void set(Row row, Row header, String name, String value) {
        row.getCell(column(header, name)).setCellValue(value);
    }

    private static int column(Row header, String name) {
        for (Cell cell : header) {
            if (name.equals(cell.getStringCellValue())) {
                return cell.getColumnIndex();
            }
        }
        throw new IllegalArgumentException("缺少表头: " + name);
    }
}