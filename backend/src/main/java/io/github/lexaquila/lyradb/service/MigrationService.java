package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.MigrationRequest;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨库数据迁移服务（PRD F8）
 *
 * <p>
 * 从源连接读取表数据，批量写入目标连接。支持：
 * <ul>
 *   <li>create：根据源表结构在目标库建表（best-effort 类型透传，跨方言可能需手工调整）</li>
 *   <li>append：追加到已存在的目标表</li>
 * </ul>
 * </p>
 *
 * <p>
 * 限制（MVP）：
 * <ul>
 *   <li>类型映射为透传，跨方言类型不兼容时建表会失败，建议 append 模式 + 预建表</li>
 *   <li>读取受 maxRows 上限保护（默认 10 万）</li>
 *   <li>无断点续传/增量</li>
 * </ul>
 * </p>
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final ConnectionService connectionService;

    public MigrationService(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * 执行迁移
     */
    public Map<String, Object> migrate(MigrationRequest req) {
        Map<String, Object> result = new HashMap<>();
        long start = System.currentTimeMillis();
        int rowsRead = 0;
        int rowsWritten = 0;
        List<String> errors = new ArrayList<>();

        try {
            ConnectionService.ActiveConnection src = connectionService.getActiveConnection(req.getSourceConnectionId());
            ConnectionService.ActiveConnection tgt = connectionService.getActiveConnection(req.getTargetConnectionId());

            DatabaseDriver srcDriver = src.driver;
            DatabaseDriver tgtDriver = tgt.driver;

            // 1. 读取源表结构
            List<ColumnMetadata> columns = srcDriver.getTableColumns(src.connection, req.getSourceSchema(), req.getSourceTable());
            if (columns.isEmpty()) {
                throw new RuntimeException("源表无列或不存在: " + req.getSourceTable());
            }
            List<String> colNames = columns.stream().map(ColumnMetadata::getName).toList();

            // 2. 建表（create 模式）
            if ("create".equalsIgnoreCase(req.getMode())) {
                String createSql = buildCreateTable(req, columns);
                try {
                    tgtDriver.executeUpdate(tgt.connection, createSql);
                } catch (Exception e) {
                    errors.add("建表失败: " + e.getMessage() + "（建议改用 append 模式并预建目标表）");
                }
            }

            // 3. 读取源数据
            String selectSql = "SELECT " + String.join(", ", quoteIdentifiers(colNames))
                    + " FROM " + qualifyTable(req.getSourceSchema(), req.getSourceTable());
            QueryResult data = srcDriver.executeQuery(src.connection, selectSql, req.getMaxRows() > 0 ? req.getMaxRows() : 100000);
            rowsRead = (int) data.getTotalRows();

            if (data.getRows() == null || data.getRows().isEmpty()) {
                result.put("success", true);
                result.put("rowsRead", 0);
                result.put("rowsWritten", 0);
                result.put("elapsedMs", System.currentTimeMillis() - start);
                result.put("message", "源表无数据");
                return result;
            }

            // 4. 批量写入目标
            int batchSize = req.getBatchSize() > 0 ? req.getBatchSize() : 1000;
            String targetRef = qualifyTable(req.getTargetSchema(), req.getTargetTable());
            List<Map<String, Object>> rows = data.getRows();

            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                // 用 colNames 顺序提取值
                Map<String, Object> ordered = new HashMap<>();
                for (String c : colNames) {
                    ordered.put(c, row.get(c));
                }
                batch.add(ordered);

                if (batch.size() >= batchSize || i == rows.size() - 1) {
                    try {
                        String insertSql = buildBatchInsert(targetRef, colNames, batch);
                        tgtDriver.executeUpdate(tgt.connection, insertSql);
                        rowsWritten += batch.size();
                    } catch (Exception e) {
                        errors.add("批次写入失败 (offset=" + i + "): " + e.getMessage());
                    }
                    batch.clear();
                }
            }

        } catch (Exception e) {
            log.error("迁移失败: {}", e.getMessage(), e);
            errors.add("迁移失败: " + e.getMessage());
        }

        result.put("success", errors.isEmpty());
        result.put("rowsRead", rowsRead);
        result.put("rowsWritten", rowsWritten);
        result.put("errors", errors);
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 构造建表 SQL（类型透传 + 主键）
     */
    private String buildCreateTable(MigrationRequest req, List<ColumnMetadata> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualifyTable(req.getTargetSchema(), req.getTargetTable())).append(" (\n");
        List<String> pk = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata c = columns.get(i);
            sb.append("  ").append(c.getName()).append(" ").append(c.getTypeName());
            if (c.getColumnSize() > 0) {
                sb.append("(").append(c.getColumnSize());
                if (c.getDecimalDigits() > 0) sb.append(",").append(c.getDecimalDigits());
                sb.append(")");
            }
            if (!c.isNullable()) sb.append(" NOT NULL");
            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
            if (c.isPrimaryKey()) pk.add(c.getName());
        }
        if (!pk.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(String.join(", ", pk)).append(")\n");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 构造多值批量 INSERT（单语句，VALUES (...),(...)）
     */
    private String buildBatchInsert(String targetRef, List<String> colNames, List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(targetRef).append(" (")
                .append(String.join(", ", quoteIdentifiers(colNames))).append(") VALUES ");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("(");
            Map<String, Object> row = batch.get(i);
            for (int j = 0; j < colNames.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(sqlLiteral(row.get(colNames.get(j))));
            }
            sb.append(")");
        }
        return sb.toString();
    }

    /** SQL 字面量 */
    private String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    private String qualifyTable(String schema, String table) {
        return schema != null && !schema.isEmpty() ? schema + "." + table : table;
    }

    private List<String> quoteIdentifiers(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add(id);
        }
        return out;
    }
}
