package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.QueryService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 数据导出REST控制器
 *
 * <p>
 * 提供查询结果的多种格式导出，支持CSV/JSON/Excel/SQL INSERT四种格式。
 * 后端流式写入HttpServletResponse OutputStream，避免大结果集OOM。
 * </p>
 *
 * <p>
 * API路径：
 * </p>
 * <ul>
 * <li>POST /api/query/{connectionId}/export - 导出查询结果</li>
 * </ul>
 */
@RestController
@RequestMapping("/query")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final QueryService queryService;
    private final ObjectMapper objectMapper;

    public ExportController(QueryService queryService, ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出查询结果
     *
     * <p>
     * 请求体格式:
     * {
     * "sql": "SELECT * FROM users",
     * "format": "csv|json|excel|sql",
     * "limit": 100000,
     * "defaultDatabase": "mydb",
     * "tableName": "users" // for SQL INSERT format
     * }
     * </p>
     */
    @PostMapping("/{connectionId}/export")
    public void exportQuery(
            @PathVariable String connectionId,
            @RequestBody Map<String, Object> request,
            HttpServletResponse response) {

        String sql = (String) request.get("sql");
        String format = (String) request.getOrDefault("format", "csv");
        String defaultDatabase = (String) request.get("defaultDatabase");
        String tableName = (String) request.get("tableName");

        int limit = 100000;
        Object limitObj = request.get("limit");
        if (limitObj instanceof Number) {
            limit = ((Number) limitObj).intValue();
        }

        if (sql == null || sql.trim().isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "SQL不能为空");
            return;
        }

        try {
            log.info("导出查询: connectionId={}, format={}, sql={}", connectionId, format,
                    sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

            QueryResult result = queryService.executeQueryForExport(connectionId, sql, defaultDatabase, limit);
            log.info("导出查询完成: 行数={}", result.getTotalRows());

            String ext = format.equalsIgnoreCase("excel") ? "xlsx" : format.toLowerCase();
            String filename = "query_result_" + System.currentTimeMillis() + "." + ext;

            response.setContentType(getContentType(format));
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

            OutputStream out = response.getOutputStream();

            switch (format.toLowerCase()) {
                case "json":
                    writeJson(result, out);
                    break;
                case "excel":
                    writeExcel(result, out);
                    break;
                case "sql":
                    writeSqlInsert(result, out, tableName);
                    break;
                case "csv":
                default:
                    writeCsv(result, out);
                    break;
            }

            out.flush();
        } catch (Exception e) {
            log.error("导出失败: {} - {}", connectionId, e.getMessage(), e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "导出失败: " + e.getMessage());
        }
    }

    private String getContentType(String format) {
        switch (format.toLowerCase()) {
            case "json":
                return "application/json;charset=UTF-8";
            case "excel":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "sql":
                return "text/plain;charset=UTF-8";
            default:
                return "text/csv;charset=UTF-8";
        }
    }

    /**
     * CSV导出 (OpenCSV)
     */
    private void writeCsv(QueryResult result, OutputStream out) throws Exception {
        // Write UTF-8 BOM for Excel compatibility
        out.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try (CSVWriter writer = new CSVWriter(osw)) {
            String[] columns = result.getColumns().toArray(new String[0]);
            writer.writeNext(columns);

            for (Map<String, Object> row : result.getRows()) {
                String[] values = new String[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    Object val = row.get(columns[i]);
                    values[i] = val == null ? "" : String.valueOf(val);
                }
                writer.writeNext(values);
            }
        }
    }

    /**
     * JSON导出 (Jackson Streaming API)
     */
    private void writeJson(QueryResult result, OutputStream out) throws Exception {
        try (JsonGenerator gen = objectMapper.getFactory().createGenerator(out)) {
            gen.writeStartArray();
            for (Map<String, Object> row : result.getRows()) {
                gen.writeStartObject();
                for (String col : result.getColumns()) {
                    Object val = row.get(col);
                    if (val == null) {
                        gen.writeNullField(col);
                    } else if (val instanceof Number) {
                        gen.writeNumberField(col, ((Number) val).doubleValue());
                    } else if (val instanceof Boolean) {
                        gen.writeBooleanField(col, (Boolean) val);
                    } else {
                        gen.writeStringField(col, String.valueOf(val));
                    }
                }
                gen.writeEndObject();
            }
            gen.writeEndArray();
        }
    }

    /**
     * Excel导出 (Apache POI SXSSF - streaming, low memory)
     */
    private void writeExcel(QueryResult result, OutputStream out) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            SXSSFSheet sheet = wb.createSheet("Query Result");

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < result.getColumns().size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(result.getColumns().get(i));
            }

            // Data rows
            int rowIdx = 1;
            for (Map<String, Object> row : result.getRows()) {
                Row dataRow = sheet.createRow(rowIdx++);
                for (int i = 0; i < result.getColumns().size(); i++) {
                    Cell cell = dataRow.createCell(i);
                    Object val = row.get(result.getColumns().get(i));
                    if (val == null) {
                        cell.setBlank();
                    } else if (val instanceof Number) {
                        cell.setCellValue(((Number) val).doubleValue());
                    } else if (val instanceof Boolean) {
                        cell.setCellValue((Boolean) val);
                    } else {
                        cell.setCellValue(String.valueOf(val));
                    }
                }
            }

            // Auto-size columns (limit to first 50 columns for performance)
            int colCount = Math.min(result.getColumns().size(), 50);
            for (int i = 0; i < colCount; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            wb.dispose();
        }
    }

    /**
     * SQL INSERT导出
     */
    private void writeSqlInsert(QueryResult result, OutputStream out, String tableName) throws Exception {
        String table = (tableName != null && !tableName.isEmpty()) ? tableName : "export_table";

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        String[] columns = result.getColumns().toArray(new String[0]);
        String colList = String.join(", ", columns);

        for (Map<String, Object> row : result.getRows()) {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ").append(table).append(" (").append(colList).append(") VALUES (");

            for (int i = 0; i < columns.length; i++) {
                if (i > 0)
                    sb.append(", ");
                Object val = row.get(columns[i]);
                if (val == null) {
                    sb.append("NULL");
                } else if (val instanceof Number) {
                    sb.append(val);
                } else if (val instanceof Boolean) {
                    sb.append(val);
                } else {
                    String s = String.valueOf(val).replace("'", "''");
                    sb.append("'").append(s).append("'");
                }
            }

            sb.append(");");
            pw.println(sb.toString());
        }

        pw.flush();
    }

    private void sendError(HttpServletResponse response, int status, String message) {
        try {
            if (!response.isCommitted()) {
                response.setStatus(status);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
            }
        } catch (Exception e) {
            log.error("发送错误响应失败", e);
        }
    }
}
