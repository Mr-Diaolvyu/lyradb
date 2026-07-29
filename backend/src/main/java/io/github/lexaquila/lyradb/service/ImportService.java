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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据导入服务。JDBC 导入使用 PreparedStatement 与单事务，失败整体回滚；
 * 文件、行、列和单元格均有服务端硬上限。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int BATCH_SIZE = 500;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 500;
    private static final int MAX_CELL_CHARS = 1_000_000;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectionService connectionService;
    private final QueryHistoryService queryHistoryService;

    public ImportService(ConnectionService connectionService, QueryHistoryService queryHistoryService) {
        this.connectionService = connectionService;
        this.queryHistoryService = queryHistoryService;
    }

    public Map<String, Object> importRows(String connectionId, String schema, String table,
            List<Map<String, Object>> rows) {
        validateInput(schema, table, rows);
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        DatabaseDriver driver = active.driver;
        String dbType = driver.getDriverInfo() != null ? driver.getDriverInfo().getDbType() : null;

        if (driver.getCapabilities() != null
                && (driver.getCapabilities().isReadOnly()
                || !driver.getCapabilities().isSupportsDML())) {
            throw new IllegalStateException("该数据库为只读/不支持 DML，无法导入");
        }

        Set<String> colSet = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            colSet.addAll(row.keySet());
        }
        if (colSet.isEmpty() || colSet.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("导入列数必须在 1-" + MAX_COLUMNS + " 之间");
        }
        List<String> columns = new ArrayList<>(colSet);
        columns.forEach(this::validateIdentifier);

        int inserted;
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            if (!(active.connection instanceof Connection jdbc)) {
                throw new IllegalStateException("数据导入仅支持具备事务能力的 JDBC 数据库");
            }
            inserted = importJdbc(jdbc, schema, table, columns, rows);
        } catch (Exception e) {
            queryHistoryService.record(connectionId, dbType,
                    "IMPORT " + table + " (" + rows.size() + " rows)",
                    0L, 0L, false, e.getClass().getSimpleName());
            throw new IllegalStateException("导入失败，未提交数据", e);
        }

        queryHistoryService.record(connectionId, dbType,
                "IMPORT " + table + " (" + rows.size() + " rows)",
                0L, (long) inserted, true, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("inserted", inserted);
        result.put("total", rows.size());
        result.put("errors", List.of());
        return result;
    }

    public Map<String, Object> importFile(String connectionId, String schema, String table,
            MultipartFile file, String format) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("导入文件不得超过 20 MiB");
        }
        String fmt = format;
        if (fmt == null || fmt.isBlank()) {
            String name = file.getOriginalFilename();
            fmt = name != null && name.toLowerCase().endsWith(".json") ? "json" : "csv";
        }
        if (!"json".equalsIgnoreCase(fmt) && !"csv".equalsIgnoreCase(fmt)) {
            throw new IllegalArgumentException("format 仅支持 csv/json");
        }
        try {
            List<Map<String, Object>> rows = "json".equalsIgnoreCase(fmt)
                    ? parseJson(file) : parseCsv(file);
            return importRows(connectionId, schema, table, rows);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("导入文件解析失败: {}", e.getClass().getSimpleName());
            throw new IllegalArgumentException("文件解析失败");
        }
    }

    private int importJdbc(Connection jdbc, String schema, String table,
            List<String> columns, List<Map<String, Object>> rows) throws Exception {
        boolean originalAutoCommit = jdbc.getAutoCommit();
        String detectedQuote = jdbc.getMetaData().getIdentifierQuoteString();
        final String quote = detectedQuote == null || detectedQuote.isBlank()
                ? "" : detectedQuote;
        String tableRef = qualify(schema, table, quote);
        String columnList = columns.stream().map(c -> quote(c, quote))
                .collect(java.util.stream.Collectors.joining(", "));
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + tableRef + " (" + columnList + ") VALUES (" + placeholders + ")";

        try {
            jdbc.setAutoCommit(false);
            int inserted = 0;
            try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                int pending = 0;
                for (Map<String, Object> row : rows) {
                    for (int index = 0; index < columns.size(); index++) {
                        Object value = row.get(columns.get(index));
                        validateCell(value);
                        statement.setObject(index + 1, value);
                    }
                    statement.addBatch();
                    pending++;
                    if (pending >= BATCH_SIZE) {
                        inserted += countBatch(statement.executeBatch(), pending);
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    inserted += countBatch(statement.executeBatch(), pending);
                }
            }
            jdbc.commit();
            return inserted;
        } catch (Exception e) {
            try {
                jdbc.rollback();
            } catch (Exception rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            try {
                jdbc.setAutoCommit(originalAutoCommit);
            } catch (Exception e) {
                log.error("恢复连接 autoCommit 失败，连接将被上层关闭");
                throw e;
            }
        }
    }

    private int countBatch(int[] counts, int expected) {
        int total = 0;
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException("批量写入失败");
            }
            total += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
        }
        return counts.length == 0 ? expected : total;
    }

    private List<Map<String, Object>> parseJson(MultipartFile file) throws Exception {
        try (var in = file.getInputStream()) {
            List<Map<String, Object>> rows = MAPPER.readValue(in,
                    new TypeReference<List<Map<String, Object>>>() { });
            ensureRowLimit(rows.size());
            return rows;
        }
    }

    private List<Map<String, Object>> parseCsv(MultipartFile file) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null || header.length == 0 || header.length > MAX_COLUMNS) {
                throw new IllegalArgumentException("CSV 表头列数无效");
            }
            String[] line;
            while ((line = reader.readNext()) != null) {
                if (line.length == 1 && (line[0] == null || line[0].isBlank())) {
                    continue;
                }
                ensureRowLimit(rows.size() + 1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    String value = i < line.length ? line[i] : null;
                    validateCell(value);
                    row.put(header[i].trim(), value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void validateInput(String schema, String table, List<Map<String, Object>> rows) {
        validateIdentifier(table);
        if (schema != null && !schema.isBlank()) {
            validateIdentifier(schema);
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("无数据行");
        }
        ensureRowLimit(rows.size());
    }

    private void ensureRowLimit(int size) {
        if (size > MAX_ROWS) {
            throw new IllegalArgumentException("导入行数不得超过 " + MAX_ROWS);
        }
    }

    private void validateIdentifier(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("表名、Schema 和列名只能包含安全标识符字符");
        }
    }

    private void validateCell(Object value) {
        if (value != null && value.toString().length() > MAX_CELL_CHARS) {
            throw new IllegalArgumentException("单元格内容超过 100 万字符限制");
        }
    }

    private String qualify(String schema, String table, String quote) {
        return schema != null && !schema.isBlank()
                ? quote(schema, quote) + "." + quote(table, quote)
                : quote(table, quote);
    }

    private String quote(String identifier, String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

}
