package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据导入服务（CSV/JSON 批量 INSERT）
 *
 * <p>对个人版连接生效：经 {@link ConnectionService} 取活跃连接 → 能力校验（只读拒）→
 * 批量多值 INSERT → 记录查询历史。受驱动能力/连接超时保护。</p>
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int BATCH_SIZE = 500;

    private final ConnectionService connectionService;
    private final QueryHistoryService queryHistoryService;

    public ImportService(ConnectionService connectionService, QueryHistoryService queryHistoryService) {
        this.connectionService = connectionService;
        this.queryHistoryService = queryHistoryService;
    }

    /**
     * @param connectionId 个人版连接 ID
     * @param schema       可选
     * @param table        目标表
     * @param rows         行数据（每行 col→value）
     * @return {success, inserted, errors}
     */
    public Map<String, Object> importRows(String connectionId, String schema, String table,
                                          List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "无数据行");
            return result;
        }
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        DatabaseDriver driver = active.driver;
        String dbType = driver.getDriverInfo() != null ? driver.getDriverInfo().getDbType() : null;

        // 能力校验
        if (driver.getCapabilities() != null
                && (driver.getCapabilities().isReadOnly() || !driver.getCapabilities().isSupportsDML())) {
            result.put("success", false);
            result.put("message", "该数据库为只读/不支持 DML，无法导入");
            return result;
        }

        // 列：所有行的键并集（保序）
        Set<String> colSet = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) colSet.addAll(r.keySet());
        List<String> columns = new ArrayList<>(colSet);
        String tableRef = (schema != null && !schema.isBlank()) ? schema + "." + table : table;

        int inserted = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            String sql = buildBatchInsert(tableRef, columns, batch);
            try {
                int affected = driver.executeUpdate(active.connection, sql);
                inserted += (affected > 0 ? affected : batch.size());
            } catch (Exception e) {
                log.warn("导入批次失败 offset={} : {}", i, e.getMessage());
                errors.add("批次 " + i + ": " + e.getMessage());
            }
        }

        queryHistoryService.record(connectionId, dbType,
                "INSERT INTO " + tableRef + " ... (" + rows.size() + " rows)", 0L, (long) inserted, true, null);

        result.put("success", errors.isEmpty());
        result.put("inserted", inserted);
        result.put("total", rows.size());
        result.put("errors", errors);
        return result;
    }

    private String buildBatchInsert(String tableRef, List<String> columns, List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableRef).append(" (");
        sb.append(String.join(", ", columns)).append(") VALUES ");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("(");
            Map<String, Object> row = batch.get(i);
            for (int j = 0; j < columns.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(sqlLiteral(row.get(columns.get(j))));
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }
}
