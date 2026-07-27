package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据导入服务（CSV/JSON 批量 INSERT）
 *
 * <p>
 * 对个人版连接生效：经 {@link ConnectionService} 取活跃连接 → 能力校验（只读拒）→
 * 批量多值 INSERT → 记录查询历史。受驱动能力/连接超时保护。
 * 支持直传行数据与 multipart 文件上传（CSV 首行表头 / JSON 数组）两种入口。
 * </p>
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int BATCH_SIZE = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        for (Map<String, Object> r : rows)
            colSet.addAll(r.keySet());
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

    /**
     * multipart 文件导入：解析为行数据后复用 {@link #importRows}
     *
     * @param format csv/json，缺省按文件后缀识别（.json → json，其余 → csv）
     */
    public Map<String, Object> importFile(String connectionId, String schema, String table,
            MultipartFile file, String format) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "文件为空");
            return result;
        }
        String fmt = format;
        if (fmt == null || fmt.isBlank()) {
            String name = file.getOriginalFilename();
            fmt = (name != null && name.toLowerCase().endsWith(".json")) ? "json" : "csv";
        }
        List<Map<String, Object>> rows;
        try {
            rows = "json".equalsIgnoreCase(fmt) ? parseJson(file) : parseCsv(file);
        } catch (Exception e) {
            log.warn("导入文件解析失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "文件解析失败: " + e.getMessage());
            return result;
        }
        return importRows(connectionId, schema, table, rows);
    }

    /** JSON 数组 → 行数据 */
    private List<Map<String, Object>> parseJson(MultipartFile file) throws Exception {
        try (var in = file.getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<List<Map<String, Object>>>() {
            });
        }
    }

    /** CSV（首行表头）→ 行数据 */
    private List<Map<String, Object>> parseCsv(MultipartFile file) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null || header.length == 0) {
                throw new IllegalArgumentException("CSV 缺少表头行");
            }
            String[] line;
            while ((line = reader.readNext()) != null) {
                if (line.length == 1 && (line[0] == null || line[0].isBlank())) {
                    continue; // 跳过空行
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i].trim(), i < line.length ? line[i] : null);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private String buildBatchInsert(String tableRef, List<String> columns, List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableRef).append(" (");
        sb.append(String.join(", ", columns)).append(") VALUES ");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0)
                sb.append(",");
            sb.append("(");
            Map<String, Object> row = batch.get(i);
            for (int j = 0; j < columns.size(); j++) {
                if (j > 0)
                    sb.append(",");
                sb.append(sqlLiteral(row.get(columns.get(j))));
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private String sqlLiteral(Object v) {
        if (v == null)
            return "NULL";
        if (v instanceof Number || v instanceof Boolean)
            return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }
}
