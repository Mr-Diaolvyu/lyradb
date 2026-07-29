package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import io.github.lexaquila.lyradb.service.QueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据导出 REST 控制器。结果逐行写入响应，避免完整结果集驻留堆内存。
 */
@RestController
@RequestMapping("/query")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);
    private static final Pattern SAFE_TABLE =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*(\\.[\\p{L}_][\\p{L}\\p{N}_$]*)?");

    private final QueryService queryService;
    private final ObjectMapper objectMapper;

    public ExportController(QueryService queryService, ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{connectionId}/export")
    public void exportQuery(@PathVariable String connectionId,
            @RequestBody Map<String, Object> request, HttpServletResponse response) {
        String sql = request.get("sql") instanceof String value ? value : null;
        String format = request.getOrDefault("format", "csv").toString().toLowerCase(Locale.ROOT);
        String defaultDatabase = request.get("defaultDatabase") instanceof String value ? value : null;
        String tableName = request.get("tableName") instanceof String value ? value : "export_table";
        Integer limit = request.get("limit") instanceof Number value ? value.intValue() : null;

        try {
            QueryService.validateExportSql(sql);
            if (!List.of("csv", "json", "excel", "sql").contains(format)) {
                throw new IllegalArgumentException("format 仅支持 csv/json/excel/sql");
            }
            if (limit != null && (limit < 1 || limit > QueryService.MAX_EXPORT_ROWS)) {
                throw new IllegalArgumentException("limit 必须在 1-"
                        + QueryService.MAX_EXPORT_ROWS + " 之间");
            }
            if ("sql".equals(format) && !SAFE_TABLE.matcher(tableName).matches()) {
                throw new IllegalArgumentException("tableName 不是安全标识符");
            }

            String extension = "excel".equals(format) ? "xlsx" : format;
            response.setContentType(contentType(format));
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"query_result_" + System.currentTimeMillis()
                            + "." + extension + "\"");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

            try (StreamingWriter writer = createWriter(
                    format, response.getOutputStream(), tableName)) {
                QueryService.ExportSummary summary = queryService.streamQueryForExport(
                        connectionId, sql, defaultDatabase, limit, writer);
                log.info("导出完成: connectionId={}, format={}, rows={}, truncated={}",
                        connectionId, format, summary.rowCount(), summary.truncated());
            }
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("导出失败: connectionId={}, type={}",
                    connectionId, e.getClass().getSimpleName(), e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "导出失败，请缩小结果范围后重试");
        }
    }

    private StreamingWriter createWriter(String format, OutputStream output, String tableName)
            throws Exception {
        return switch (format) {
            case "json" -> new JsonStreamingWriter(objectMapper, output);
            case "excel" -> new ExcelStreamingWriter(output);
            case "sql" -> new SqlStreamingWriter(output, tableName);
            default -> new CsvStreamingWriter(output);
        };
    }

    private String contentType(String format) {
        return switch (format) {
            case "json" -> "application/json;charset=UTF-8";
            case "excel" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "sql" -> "application/sql;charset=UTF-8";
            default -> "text/csv;charset=UTF-8";
        };
    }

    private void sendError(HttpServletResponse response, int status, String message) {
        try {
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(status);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Map.of("success", false, "message", message, "status", status)));
            }
        } catch (Exception e) {
            log.error("发送导出错误响应失败", e);
        }
    }

    private interface StreamingWriter extends QueryService.ExportConsumer, AutoCloseable {
        @Override
        void close() throws Exception;
    }

    private static final class CsvStreamingWriter implements StreamingWriter {
        private final CSVWriter writer;
        private List<String> columns = List.of();

        CsvStreamingWriter(OutputStream output) {
            this.writer = new CSVWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        }

        @Override
        public void onColumns(List<String> columns) {
            this.columns = columns;
            writer.writeNext(columns.stream().map(ExportController::safeSpreadsheetText)
                    .toArray(String[]::new));
        }

        @Override
        public void onRow(Map<String, Object> row) {
            String[] values = new String[columns.size()];
            for (int index = 0; index < columns.size(); index++) {
                Object value = row.get(columns.get(index));
                values[index] = value == null ? "" : value instanceof String text
                        ? safeSpreadsheetText(text) : String.valueOf(value);
            }
            writer.writeNext(values);
        }

        @Override
        public void close() throws Exception {
            writer.flush();
        }
    }

    private static final class JsonStreamingWriter implements StreamingWriter {
        private final JsonGenerator generator;

        JsonStreamingWriter(ObjectMapper mapper, OutputStream output) throws Exception {
            generator = mapper.getFactory().createGenerator(output);
            generator.writeStartArray();
        }

        @Override
        public void onColumns(List<String> columns) {
            // JSON 行对象已携带列名，无需额外头部。
        }

        @Override
        public void onRow(Map<String, Object> row) throws Exception {
            generator.writeObject(row);
        }

        @Override
        public void close() throws Exception {
            generator.writeEndArray();
            generator.flush();
        }
    }

    private static final class ExcelStreamingWriter implements StreamingWriter {
        private final OutputStream output;
        private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        private final SXSSFSheet sheet = workbook.createSheet("Query Result");
        private List<String> columns = List.of();
        private int rowIndex;

        ExcelStreamingWriter(OutputStream output) {
            this.output = output;
        }

        @Override
        public void onColumns(List<String> columns) {
            this.columns = columns;
            Row header = sheet.createRow(rowIndex++);
            for (int index = 0; index < columns.size(); index++) {
                header.createCell(index).setCellValue(safeSpreadsheetText(columns.get(index)));
            }
        }

        @Override
        public void onRow(Map<String, Object> row) {
            Row data = sheet.createRow(rowIndex++);
            for (int index = 0; index < columns.size(); index++) {
                Cell cell = data.createCell(index);
                Object value = row.get(columns.get(index));
                if (value == null) {
                    cell.setBlank();
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else if (value instanceof Boolean bool) {
                    cell.setCellValue(bool);
                } else {
                    cell.setCellValue(safeSpreadsheetText(String.valueOf(value)));
                }
            }
        }

        @Override
        public void close() throws Exception {
            try {
                workbook.write(output);
            } finally {
                workbook.dispose();
                workbook.close();
            }
        }
    }

    private static final class SqlStreamingWriter implements StreamingWriter {
        private final PrintWriter writer;
        private final String tableName;
        private List<String> columns = List.of();

        SqlStreamingWriter(OutputStream output, String tableName) {
            this.writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            this.tableName = tableName;
        }

        @Override
        public void onColumns(List<String> columns) {
            this.columns = columns;
        }

        @Override
        public void onRow(Map<String, Object> row) {
            String columnList = columns.stream().map(SqlStreamingWriter::quoteIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));
            String values = columns.stream().map(column -> sqlLiteral(row.get(column)))
                    .collect(java.util.stream.Collectors.joining(", "));
            writer.println("INSERT INTO " + quoteTable(tableName) + " (" + columnList
                    + ") VALUES (" + values + ");");
        }

        @Override
        public void close() {
            writer.flush();
        }

        private static String quoteTable(String table) {
            return java.util.Arrays.stream(table.split("\\."))
                    .map(SqlStreamingWriter::quoteIdentifier)
                    .collect(java.util.stream.Collectors.joining("."));
        }

        private static String quoteIdentifier(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }

        private static String sqlLiteral(Object value) {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
    }

    static String safeSpreadsheetText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) {
            return "'" + value;
        }
        return value;
    }
}
