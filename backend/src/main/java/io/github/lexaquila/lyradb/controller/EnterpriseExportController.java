package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.ConnectionService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 企业导出控制器（导出一律需审批）
 *
 * <p>
 * POST /api/ent/export?approvalRequestId=xxx body {grantedSourceName, sql,
 * format, defaultDatabase?}
 * </p>
 *
 * <p>
 * 校验审批单为 APPROVED 且匹配该数据源与 EXPORT 操作 → 执行(限 10 万行) →
 * 流式写 CSV/JSON → 审计 → 标记审批 DONE。
 * </p>
 */
@RestController
@RequestMapping("/ent")
public class EnterpriseExportController {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseExportController.class);
    private static final int EXPORT_LIMIT = 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalService approvalService;
    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;

    public EnterpriseExportController(ApprovalService approvalService, GrantService grantService,
            DataSourceService dataSourceService, AuditService auditService,
            SecurityUtil securityUtil) {
        this.approvalService = approvalService;
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
    }

    @PostMapping("/export")
    public void export(@RequestParam("approvalRequestId") String approvalRequestId,
            @RequestBody Map<String, String> body,
            HttpServletResponse response) throws Exception {
        User user = securityUtil.currentUser();
        if (user == null)
            throw new RuntimeException("未登录");

        // 1. 校验审批单
        ApprovalRequest approval = approvalService.get(approvalRequestId);
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new RuntimeException("导出审批未通过，当前状态: " + approval.getStatus());
        }
        if (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("导出审批已过期，请重新申请");
        }
        if (!"EXPORT".equals(approval.getOperationType())) {
            throw new RuntimeException("审批单类型不是 EXPORT");
        }

        String grantedSourceName = body.get("grantedSourceName");
        String sql = body.get("sql");
        String format = body.get("format");
        String defaultDatabase = body.get("defaultDatabase");
        if (grantedSourceName == null || sql == null) {
            throw new RuntimeException("grantedSourceName 和 sql 必填");
        }
        if (approval.getGrantedSourceName() != null
                && !approval.getGrantedSourceName().equals(grantedSourceName)) {
            throw new RuntimeException("审批单数据源与请求数据源不一致");
        }
        if (format == null || format.isBlank())
            format = "csv";
        if (!"csv".equals(format) && !"json".equals(format)) {
            // Excel/SQL 在企业导出暂以 CSV 兜底（复用最少依赖）
            format = "csv";
        }

        // 2. 解析授权 → 连接
        Grant grant = grantService.resolveForUser(user.getId(), grantedSourceName);
        String dbType = dataSourceService.getEntity(grant.getDataSourceId()).getDbType();
        long start = System.currentTimeMillis();
        QueryResult result;
        try {
            ConnectionService.ActiveConnection ac = dataSourceService.resolveActiveConnection(grant.getDataSourceId());
            if (defaultDatabase != null && !defaultDatabase.isBlank()
                    && ac.connection instanceof java.sql.Connection jdbc) {
                try {
                    jdbc.setCatalog(defaultDatabase);
                } catch (Exception ignored) {
                }
            }
            result = ac.driver.executeQuery(ac.connection, sql, EXPORT_LIMIT);
        } catch (Exception e) {
            audit(user, grant, dbType, sql, 0, false, "导出失败: " + e.getMessage(), start);
            throw new RuntimeException("导出查询失败: " + e.getMessage(), e);
        }

        // 3. 流式写出
        try {
            String filename = "export_" + System.currentTimeMillis() + "." + format;
            response.setContentType("csv".equals(format) ? "text/csv;charset=UTF-8" : "application/json;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            List<String> cols = result.getColumns();
            List<Map<String, Object>> rows = result.getRows();
            if ("csv".equals(format)) {
                java.io.OutputStream os = response.getOutputStream();
                // UTF-8 BOM，保证 Excel 正确识别中文
                os.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
                CSVWriter csv = new CSVWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                csv.writeNext(cols.toArray(new String[0]));
                for (Map<String, Object> row : rows) {
                    String[] values = new String[cols.size()];
                    for (int i = 0; i < cols.size(); i++) {
                        Object v = row.get(cols.get(i));
                        values[i] = v == null ? "" : String.valueOf(v);
                    }
                    csv.writeNext(values);
                }
                csv.flush();
            } else {
                Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
                w.write("[");
                for (int r = 0; r < rows.size(); r++) {
                    if (r > 0)
                        w.write(",");
                    w.write(MAPPER.writeValueAsString(rows.get(r)));
                }
                w.write("]");
                w.flush();
            }
        } catch (Exception e) {
            log.error("导出写流失败: {}", e.getMessage());
        }

        // 4. 审计 + 标记审批完成
        audit(user, grant, dbType, sql, result.getTotalRows(), true, null, start);
        approvalService.markExecuted(approvalRequestId,
                "exported " + result.getTotalRows() + " rows as " + format, true);
    }

    private void audit(User user, Grant grant, String dbType, String sql, long rows, boolean success, String err,
            long start) {
        auditService.record(grant.getWorkspaceId(), user.getId(), user.getUsername(),
                user.getRoles().isEmpty() ? "ANALYST" : user.getRoles().get(0),
                grant.getDataSourceId(), grant.getGrantedSourceName(), dbType, "EXPORT",
                sql, rows, rows, System.currentTimeMillis() - start, success, err);
    }
}
