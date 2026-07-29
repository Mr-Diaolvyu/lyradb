
package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.EnterpriseQueryService;
import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业导出：审批载荷强绑定后，以 JDBC ResultSet 逐行脱敏并写入响应。
 */
@RestController
@RequestMapping("/ent")
public class EnterpriseExportController {

    private final ApprovalService approvalService;
    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final EnterpriseQueryService enterpriseQueryService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper;

    public EnterpriseExportController(
            ApprovalService approvalService,
            GrantService grantService,
            DataSourceService dataSourceService,
            EnterpriseQueryService enterpriseQueryService,
            AuditService auditService,
            SecurityUtil securityUtil,
            ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.enterpriseQueryService = enterpriseQueryService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/export")
    public void export(
            @RequestParam("approvalRequestId") String approvalRequestId,
            @RequestBody Map<String, String> body,
            HttpServletResponse response) throws Exception {
        User user = securityUtil.requireCurrentUser();
        String workspaceId = securityUtil.requireCurrentWorkspace();
        ApprovalRequest approvalRequest = approvalService.get(approvalRequestId);
        String grantedSourceName = required(
                approvalRequest.getGrantedSourceName(), "审批记录中的 grantedSourceName");
        String sql = required(body.get("sql"), "sql");
        String format = body.get("format") == null
                || body.get("format").isBlank()
                ? "csv"
                : body.get("format").trim().toLowerCase(Locale.ROOT);
        String defaultDatabase =
                blankToNull(body.get("defaultDatabase"));
        if (!List.of("csv", "json").contains(format)) {
            throw new IllegalArgumentException(
                    "导出格式仅支持 csv/json");
        }

        Grant grant = grantService.resolveForUser(
                user.getId(), workspaceId, grantedSourceName);
        if (!workspaceId.equals(grant.getWorkspaceId())) {
            throw new RuntimeException(
                    "逻辑数据源不属于当前工作空间");
        }
        DataSource dataSource =
                dataSourceService.getEntity(grant.getDataSourceId());
        if (!workspaceId.equals(dataSource.getWorkspaceId())) {
            throw new RuntimeException(
                    "授权与真实数据源工作空间不一致");
        }
        enterpriseQueryService.authorizeReadOnly(
                grant, sql, defaultDatabase);
        approvalService.claimForExecution(
                approvalRequestId, user, grant, "EXPORT",
                sql, format, defaultDatabase);

        long started = System.currentTimeMillis();
        long[] emittedRows = {0L};
        boolean terminalSuccess = false;
        try {
            // 在响应提交前先写入执行意图；审计不可用时导出失败关闭。
            audit(user, grant, dataSource.getDbType(),
                    "EXPORT_START", sql, 0, 0,
                    true, null, approvalRequestId);
            prepareResponse(response, format);

            EnterpriseQueryService.ExportSummary summary;
            try (StreamingWriter writer = createWriter(
                    format, response.getOutputStream())) {
                summary = enterpriseQueryService.streamExport(
                        grant, sql, defaultDatabase,
                        new EnterpriseQueryService.ExportConsumer() {
                            @Override
                            public void onColumns(List<String> columns)
                                    throws Exception {
                                writer.onColumns(columns);
                            }

                            @Override
                            public void onRow(Map<String, Object> row)
                                    throws Exception {
                                writer.onRow(row);
                                emittedRows[0]++;
                            }
                        });
            }

            approvalService.markExecutionResult(
                    approvalRequestId, true,
                    "已导出 " + summary.rowCount()
                            + " 行，格式 " + format
                            + (summary.truncated() ? "（达到授权行数上限）" : ""));
            terminalSuccess = true;
            audit(user, grant, dataSource.getDbType(),
                    "EXPORT", sql, summary.rowCount(),
                    summary.elapsedMs(), true, null,
                    approvalRequestId);
        } catch (Exception exception) {
            long elapsed = System.currentTimeMillis() - started;
            if (terminalSuccess) {
                // 数据已经输出且审批已准确标记 DONE；EXPORT_START 是执行授权证据。
                // 最终汇总审计失败必须显式报错，但绝不能把真实成功改写成 FAILED。
                logFinalAuditFailure(approvalRequestId, exception);
                throw exception;
            }
            try {
                approvalService.markExecutionResult(
                        approvalRequestId, false,
                        "导出失败: " + safeMessage(exception));
            } catch (Exception markFailure) {
                exception.addSuppressed(markFailure);
            }
            try {
                audit(user, grant, dataSource.getDbType(),
                        "EXPORT", sql, emittedRows[0], elapsed,
                        false, safeMessage(exception),
                        approvalRequestId);
            } catch (Exception auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        }
    }

    private static void logFinalAuditFailure(
            String approvalRequestId, Exception exception) {
        org.slf4j.LoggerFactory.getLogger(EnterpriseExportController.class)
                .error("导出已完成，但最终汇总审计失败: approvalId={}, type={}",
                        approvalRequestId, exception.getClass().getSimpleName(), exception);
    }

    private void prepareResponse(
            HttpServletResponse response, String format) {
        String filename = "export_" + System.currentTimeMillis()
                + "." + format;
        response.setContentType("csv".equals(format)
                ? "text/csv;charset=UTF-8"
                : "application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"");
        response.setHeader("Access-Control-Expose-Headers",
                "Content-Disposition");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    private StreamingWriter createWriter(
            String format, OutputStream output) throws Exception {
        return "json".equals(format)
                ? new JsonStreamingWriter(objectMapper, output)
                : new CsvStreamingWriter(output);
    }

    private void audit(User user, Grant grant, String dbType,
                       String operation, String sql, long rows,
                       long elapsed, boolean success, String error,
                       String approvalRequestId) {
        auditService.record(
                grant.getWorkspaceId(), user.getId(),
                user.getUsername(),
                securityUtil.effectiveRoles(
                                grant.getWorkspaceId())
                        .stream().findFirst().orElse("ANALYST"),
                grant.getDataSourceId(),
                grant.getGrantedSourceName(), dbType,
                operation, operation, sql, 0, rows, elapsed,
                success, error, approvalRequestId);
    }

    private interface StreamingWriter
            extends EnterpriseQueryService.ExportConsumer, AutoCloseable {
        @Override
        void close() throws Exception;
    }

    private static final class CsvStreamingWriter
            implements StreamingWriter {
        private final CSVWriter writer;
        private List<String> columns = List.of();

        private CsvStreamingWriter(OutputStream output) throws Exception {
            output.write(new byte[]{
                    (byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            this.writer = new CSVWriter(
                    new OutputStreamWriter(
                            output, StandardCharsets.UTF_8));
        }

        @Override
        public void onColumns(List<String> columns) {
            this.columns = columns;
            writer.writeNext(columns.stream()
                    .map(EnterpriseExportController::csvSafe)
                    .toArray(String[]::new));
        }

        @Override
        public void onRow(Map<String, Object> row) {
            String[] values = new String[columns.size()];
            for (int index = 0;
                 index < columns.size(); index++) {
                Object value = row.get(columns.get(index));
                values[index] = value == null
                        ? ""
                        : csvSafe(String.valueOf(value));
            }
            writer.writeNext(values);
        }

        @Override
        public void close() throws Exception {
            writer.flush();
        }
    }

    private static final class JsonStreamingWriter
            implements StreamingWriter {
        private final JsonGenerator generator;

        private JsonStreamingWriter(
                ObjectMapper mapper, OutputStream output)
                throws Exception {
            this.generator =
                    mapper.getFactory().createGenerator(output);
            generator.writeStartArray();
        }

        @Override
        public void onColumns(List<String> columns) {
            // JSON 行对象已经携带列名，无需额外头部。
        }

        @Override
        public void onRow(Map<String, Object> row)
                throws Exception {
            generator.writeObject(row);
        }

        @Override
        public void close() throws Exception {
            generator.writeEndArray();
            generator.flush();
        }
    }

    private static String csvSafe(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int index = 0;
        while (index < value.length()
                && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index < value.length()
                && "=+-@".indexOf(value.charAt(index)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String required(
            String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank()
                ? null : value.trim();
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof java.sql.SQLTimeoutException) {
            return "数据库执行超时";
        }
        if (exception instanceof java.sql.SQLException) {
            return "数据库执行失败";
        }
        if (exception instanceof org.springframework.security.access.AccessDeniedException) {
            return "权限校验失败";
        }
        if (exception instanceof IllegalArgumentException) {
            return "请求未通过安全校验";
        }
        return "操作未完成";
    }
}
