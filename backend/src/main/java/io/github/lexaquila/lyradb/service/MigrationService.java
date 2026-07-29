package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.MigrationRequest;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 跨库数据迁移服务。
 *
 * <p>严格限制迁移规模与批次；JDBC 目标的建表和写入位于同一事务，
 * 任一批次失败整体回滚。两条连接按稳定顺序加锁，避免并发串库和死锁。</p>
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*");

    private final ConnectionService connectionService;

    public MigrationService(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public Map<String, Object> migrate(MigrationRequest request) {
        validate(request);
        long start = System.currentTimeMillis();
        ConnectionService.ActiveConnection source =
                connectionService.getActiveConnection(request.getSourceConnectionId());
        ConnectionService.ActiveConnection target =
                connectionService.getActiveConnection(request.getTargetConnectionId());

        ConnectionService.ActiveConnection first = source;
        ConnectionService.ActiveConnection second = target;
        if (request.getSourceConnectionId().compareTo(request.getTargetConnectionId()) > 0) {
            first = target;
            second = source;
        }

        try (ConnectionService.ActiveConnection.Lease ignoredFirst = first.acquire()) {
            if (first == second) {
                return migrateLocked(request, source, target, start);
            }
            try (ConnectionService.ActiveConnection.Lease ignoredSecond = second.acquire()) {
                return migrateLocked(request, source, target, start);
            }
        } catch (Exception e) {
            log.error("迁移失败: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("迁移失败，JDBC 目标已回滚", e);
        }
    }

    private Map<String, Object> migrateLocked(MigrationRequest request,
            ConnectionService.ActiveConnection source,
            ConnectionService.ActiveConnection target, long start) throws Exception {
        DatabaseDriver sourceDriver = source.driver;
        DatabaseDriver targetDriver = target.driver;
        List<ColumnMetadata> columns = sourceDriver.getTableColumns(source.connection,
                request.getSourceSchema(), request.getSourceTable());
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("源表无列或不存在");
        }
        List<String> columnNames = columns.stream().map(ColumnMetadata::getName).toList();
        columnNames.forEach(this::validateIdentifier);

        String sourceQuote = source.connection instanceof Connection sourceJdbc
                ? identifierQuote(sourceJdbc) : "";
        String sourceColumns = columnNames.stream()
                .map(column -> quote(column, sourceQuote))
                .collect(java.util.stream.Collectors.joining(", "));
        String selectSql = "SELECT " + sourceColumns + " FROM "
                + qualifyQuoted(request.getSourceSchema(), request.getSourceTable(), sourceQuote);
        QueryResult data = sourceDriver.executeQuery(source.connection, selectSql, request.getMaxRows());
        int rowsRead = Math.toIntExact(data.getTotalRows());

        if (!(target.connection instanceof Connection jdbc)) {
            throw new IllegalStateException("迁移目标必须是具备事务能力的 JDBC 数据库");
        }
        int rowsWritten = migrateJdbcTarget(jdbc, targetDriver, request, columns,
                columnNames, data.getRows());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rowsRead", rowsRead);
        result.put("rowsWritten", rowsWritten);
        result.put("errors", List.of());
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    private int migrateJdbcTarget(Connection jdbc, DatabaseDriver targetDriver,
            MigrationRequest request, List<ColumnMetadata> columns,
            List<String> columnNames, List<Map<String, Object>> rows) throws Exception {
        boolean originalAutoCommit = jdbc.getAutoCommit();
        String identifierQuote = identifierQuote(jdbc);
        try {
            jdbc.setAutoCommit(false);
            if ("create".equalsIgnoreCase(request.getMode())) {
                targetDriver.executeUpdate(jdbc,
                        buildCreateTable(request, columns, identifierQuote));
            }
            int written = insertPrepared(jdbc, request, columnNames, rows, identifierQuote);
            jdbc.commit();
            return written;
        } catch (Exception e) {
            try {
                jdbc.rollback();
            } catch (Exception rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            jdbc.setAutoCommit(originalAutoCommit);
        }
    }

    private int insertPrepared(Connection jdbc, MigrationRequest request,
            List<String> columns, List<Map<String, Object>> rows,
            String identifierQuote) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String target = qualifyQuoted(request.getTargetSchema(), request.getTargetTable(),
                identifierQuote);
        String columnList = columns.stream().map(c -> quote(c, identifierQuote))
                .collect(java.util.stream.Collectors.joining(", "));
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + target + " (" + columnList + ") VALUES (" + placeholders + ")";

        int written = 0;
        int pending = 0;
        try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                for (int index = 0; index < columns.size(); index++) {
                    statement.setObject(index + 1, row.get(columns.get(index)));
                }
                statement.addBatch();
                pending++;
                if (pending >= request.getBatchSize()) {
                    written += countBatch(statement.executeBatch(), pending);
                    pending = 0;
                }
            }
            if (pending > 0) {
                written += countBatch(statement.executeBatch(), pending);
            }
        }
        return written;
    }

    /**
     * 构造建表 SQL。主键约束前始终带逗号，修复原实现生成无效 DDL 的问题。
     */
    String buildCreateTable(MigrationRequest request, List<ColumnMetadata> columns) {
        return buildCreateTable(request, columns, "");
    }

    String buildCreateTable(MigrationRequest request, List<ColumnMetadata> columns,
            String identifierQuote) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ")
                .append(qualifyQuoted(request.getTargetSchema(), request.getTargetTable(),
                        identifierQuote))
                .append(" (\n");
        List<String> primaryKeys = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            ColumnMetadata column = columns.get(index);
            sql.append("  ").append(quote(column.getName(), identifierQuote))
                    .append(" ").append(column.getTypeName());
            if (column.getColumnSize() > 0) {
                sql.append("(").append(column.getColumnSize());
                if (column.getDecimalDigits() > 0) {
                    sql.append(",").append(column.getDecimalDigits());
                }
                sql.append(")");
            }
            if (!column.isNullable()) {
                sql.append(" NOT NULL");
            }
            if (index < columns.size() - 1 || columns.stream().anyMatch(ColumnMetadata::isPrimaryKey)) {
                sql.append(",");
            }
            sql.append("\n");
            if (column.isPrimaryKey()) {
                primaryKeys.add(column.getName());
            }
        }
        if (!primaryKeys.isEmpty()) {
            String quotedPrimaryKeys = primaryKeys.stream()
                    .map(key -> quote(key, identifierQuote))
                    .collect(java.util.stream.Collectors.joining(", "));
            sql.append("  PRIMARY KEY (").append(quotedPrimaryKeys).append(")\n");
        }
        return sql.append(")").toString();
    }

    private int countBatch(int[] counts, int expected) {
        int total = 0;
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException("批量写入失败");
            }
            total += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
        }
        return counts.length == 0 ? expected : total;
    }

    private void validate(MigrationRequest request) {
        if (request == null || request.getSourceConnectionId() == null
                || request.getTargetConnectionId() == null) {
            throw new IllegalArgumentException("源连接和目标连接必填");
        }
        validateIdentifier(request.getSourceTable());
        validateIdentifier(request.getTargetTable());
        if (request.getSourceSchema() != null && !request.getSourceSchema().isBlank()) {
            validateIdentifier(request.getSourceSchema());
        }
        if (request.getTargetSchema() != null && !request.getTargetSchema().isBlank()) {
            validateIdentifier(request.getTargetSchema());
        }
        if (!"create".equalsIgnoreCase(request.getMode())
                && !"append".equalsIgnoreCase(request.getMode())) {
            throw new IllegalArgumentException("mode 仅支持 create/append");
        }
        if (request.getMaxRows() < 1 || request.getMaxRows() > MAX_ROWS) {
            throw new IllegalArgumentException("maxRows 必须在 1-" + MAX_ROWS + " 之间");
        }
        if (request.getBatchSize() < 1 || request.getBatchSize() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize 必须在 1-" + MAX_BATCH_SIZE + " 之间");
        }
    }

    private void validateIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("表名、Schema 和列名只能包含安全标识符字符");
        }
    }

    private String qualifyQuoted(String schema, String table, String quote) {
        return schema != null && !schema.isBlank()
                ? quote(schema, quote) + "." + quote(table, quote)
                : quote(table, quote);
    }

    private String identifierQuote(Connection jdbc) throws Exception {
        String detected = jdbc.getMetaData().getIdentifierQuoteString();
        return detected == null || detected.isBlank() ? "" : detected;
    }

    private String quote(String identifier, String identifierQuote) {
        if (identifierQuote == null || identifierQuote.isBlank()) {
            return identifier;
        }
        return identifierQuote
                + identifier.replace(identifierQuote, identifierQuote + identifierQuote)
                + identifierQuote;
    }

}
