package io.github.lexaquila.lyradb.transfer.connection;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionExcelTemplateCodecTest {

    private final ConnectionExcelTemplateCodec codec =
            new ConnectionExcelTemplateCodec();

    @Test
    void createsDocumentedXlsxTemplate() throws Exception {
        byte[] content = codec.createTemplate();

        assertThat(ConnectionExcelTemplateCodec.hasXlsxSignature(content)).isTrue();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheet(ConnectionExcelTemplateCodec.DATA_SHEET))
                    .isNotNull();
            assertThat(workbook.getSheet(ConnectionExcelTemplateCodec.EXAMPLE_SHEET))
                    .isNotNull();
            assertThat(workbook.getSheet(ConnectionExcelTemplateCodec.FIELD_SHEET))
                    .isNotNull();
            assertThat(workbook.getSheet(ConnectionExcelTemplateCodec.DATABASE_SHEET))
                    .isNotNull();
            Sheet data = workbook.getSheet(ConnectionExcelTemplateCodec.DATA_SHEET);
            assertThat(data.getRow(0).getCell(0).getStringCellValue())
                    .contains("明文").contains("第 3 行");
            assertThat(data.getDataValidations()).hasSize(4);
        }
    }

    @Test
    void parsesFilledTemplateAndMarksPlaintextCredentials() throws Exception {
        byte[] filled = editTemplate((workbook, row, header) -> {
            set(row, header, "连接名称 *", "开发 MySQL");
            set(row, header, "数据库类型 *", "MYSQL");
            set(row, header, "主机地址", "127.0.0.1");
            set(row, header, "端口", 3306);
            set(row, header, "数据库名", "demo");
            set(row, header, "用户名", "app");
            set(row, header, "密码（明文）", "  secret-value  ");
            set(row, header, "启用 SSL", "否");
            set(row, header, "收藏", "是");
            set(row, header, "其他参数 JSON", "{\"connectTimeout\":30}");
        });

        ConnectionPackageReadResult result = codec.read(filled);

        assertThat(result.credentialPolicy())
                .isEqualTo(CredentialExportPolicy.PLAINTEXT);
        assertThat(result.risk())
                .isEqualTo(ConnectionPackageRisk.PLAINTEXT_DATABASE_CREDENTIALS);
        assertThat(result.connections()).hasSize(1);
        ConnectionPackageEntry entry = result.connections().get(0);
        assertThat(entry.name()).isEqualTo("开发 MySQL");
        assertThat(entry.dbType()).isEqualTo("MYSQL");
        assertThat(entry.parameters())
                .containsEntry("host", "127.0.0.1")
                .containsEntry("port", 3306)
                .containsEntry("ssl", false)
                .containsEntry("connectTimeout", 30);
        assertThat(entry.credentials())
                .containsEntry("password", "  secret-value  ");
        assertThat(entry.credentialKeys()).contains("password");
        assertThat(entry.favorite()).isTrue();
    }

    @Test
    void parsesCredentialFreeTemplateAsOmitted() throws Exception {
        byte[] filled = editTemplate((workbook, row, header) -> {
            set(row, header, "连接名称 *", "本地 SQLite");
            set(row, header, "数据库类型 *", "SQLITE");
            set(row, header, "SQLite 文件路径", "D:\\data\\demo.db");
            set(row, header, "凭据字段名", "password");
        });

        ConnectionPackageReadResult result = codec.read(filled);

        assertThat(result.credentialPolicy()).isEqualTo(CredentialExportPolicy.OMIT);
        assertThat(result.connections().get(0).credentialKeys()).containsExactly("password");
        assertThat(result.connections().get(0).credentials()).isEmpty();
    }

    @Test
    void rejectsFormulaCells() throws Exception {
        byte[] filled = editTemplate((workbook, row, header) -> {
            set(row, header, "连接名称 *", "公式连接");
            set(row, header, "数据库类型 *", "MYSQL");
            row.getCell(header.indexOf("端口")).setCellFormula("1+1");
        });

        assertThatThrownBy(() -> codec.read(filled))
                .isInstanceOf(ConnectionPackageException.class)
                .extracting("code")
                .isEqualTo(ConnectionPackageException.Code.INVALID_FIELD);
    }

    @Test
    void rejectsUnsupportedDatabaseType() throws Exception {
        byte[] filled = editTemplate((workbook, row, header) -> {
            set(row, header, "连接名称 *", "未知类型");
            set(row, header, "数据库类型 *", "UNKNOWN");
        });

        assertThatThrownBy(() -> codec.read(filled))
                .isInstanceOf(ConnectionPackageException.class)
                .hasMessageContaining("不支持的数据库类型");
    }

    @Test
    void rejectsHiddenDataSheet() throws Exception {
        byte[] filled = editTemplate((workbook, row, header) -> {
            set(row, header, "连接名称 *", "隐藏数据页");
            set(row, header, "数据库类型 *", "MYSQL");
            workbook.setSheetHidden(workbook.getSheetIndex(
                    ConnectionExcelTemplateCodec.DATA_SHEET), true);
        });

        assertThatThrownBy(() -> codec.read(filled))
                .isInstanceOf(ConnectionPackageException.class)
                .hasMessageContaining("不得隐藏");
    }

    private byte[] editTemplate(TemplateEditor editor) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(codec.createTemplate()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet(ConnectionExcelTemplateCodec.DATA_SHEET);
            Row header = sheet.getRow(1);
            Row row = sheet.createRow(2);
            for (int index = 0; index < header.getLastCellNum(); index++) {
                row.createCell(index);
            }
            editor.edit(workbook, row, new HeaderLookup(header));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void set(Row row, HeaderLookup header,
            String name, String value) {
        row.getCell(header.indexOf(name)).setCellValue(value);
    }

    private static void set(Row row, HeaderLookup header,
            String name, double value) {
        row.getCell(header.indexOf(name)).setCellValue(value);
    }

    @FunctionalInterface
    private interface TemplateEditor {
        void edit(XSSFWorkbook workbook, Row row, HeaderLookup header)
                throws Exception;
    }

    private record HeaderLookup(Row row) {
        private int indexOf(String name) {
            for (Cell cell : row) {
                if (name.equals(cell.getStringCellValue())) return cell.getColumnIndex();
            }
            throw new IllegalArgumentException("缺少表头: " + name);
        }
    }
}