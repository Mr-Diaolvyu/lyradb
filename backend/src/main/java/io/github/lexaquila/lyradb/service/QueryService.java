package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 查询执行服务。
 *
 * <p>同一物理连接上的目录切换与语句执行必须持有
 * {@link ConnectionService.ActiveConnection} 独占锁；执行结束恢复原目录，
 * 防止并发请求串库。每次执行还会注册独立 executionId，以便精确取消。</p>
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** 单次导出的服务端硬上限，客户端参数不得突破。 */
    public static final int MAX_EXPORT_ROWS = 100_000;

    private final ConnectionService connectionService;
    private final AppProperties appProperties;
    private final QueryHistoryService queryHistoryService;
    private final SqlReviewService sqlReviewService;

    public QueryService(ConnectionService connectionService, AppProperties appProperties,
            QueryHistoryService queryHistoryService, SqlReviewService sqlReviewService) {
        this.connectionService = connectionService;
        this.appProperties = appProperties;
        this.queryHistoryService = queryHistoryService;
        this.sqlReviewService = sqlReviewService;
    }

    public QueryResult executeQuery(String connectionId, String sql, String defaultDatabase) throws Exception {
        return executeQuery(connectionId, sql, defaultDatabase, false);
    }

    public QueryResult executeQuery(String connectionId, String sql, String defaultDatabase, boolean force)
            throws Exception {
        return executeQuery(connectionId, sql, defaultDatabase, force, "query-" + UUID.randomUUID());
    }

    /**
     * 使用调用方提供的 executionId 执行查询，供后台任务精确取消。
     */
    public QueryResult executeQuery(String connectionId, String sql, String defaultDatabase,
            boolean force, String executionId) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        int limit = Math.max(1, appProperties.getMaxQueryRows());
        String dbType = active.driver.getDriverInfo() != null
                ? active.driver.getDriverInfo().getDbType() : null;

        List<SqlReviewFinding> findings = sqlReviewService.review(sql, dbType);
        if (!force && sqlReviewService.hasBlocking(findings)) {
            log.info("SQL 审核拦截: connectionId={}, 命中={}条", connectionId, findings.size());
            return blockedResult(sql, findings);
        }

        QueryResult result;
        try {
            result = withConnection(connectionId, active, defaultDatabase, executionId,
                    ac -> ac.driver.executeQuery(ac.connection, sql, limit));
        } catch (Exception e) {
            queryHistoryService.record(connectionId, dbType, sql, 0L, 0L, false, safeMessage(e));
            throw e;
        }

        boolean hasError = result.getColumns() != null && result.getColumns().contains("error")
                && result.getTotalRows() > 0;
        String errMsg = null;
        if (hasError && result.getRows() != null && !result.getRows().isEmpty()) {
            Object errVal = result.getRows().get(0).get("error");
            if (errVal != null) {
                errMsg = errVal.toString();
            }
        }
        queryHistoryService.record(connectionId, dbType, sql, result.getElapsedMs(),
                result.getTotalRows(), !hasError, errMsg);

        if (!findings.isEmpty()) {
            result.setReviewFindings(findings);
        }
        log.info("查询完成: connectionId={}, 耗时={}ms, 行数={}",
                connectionId, result.getElapsedMs(), result.getTotalRows());
        return result;
    }

    /**
     * 兼容连接维度取消。后台任务必须使用 {@link #cancelExecution(String)}。
     */
    public boolean cancelQuery(String connectionId) {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        boolean cancelled = StatementRegistry.cancel(active.connection);
        log.info("取消查询: connectionId={}, 结果={}", connectionId, cancelled ? "已发出取消" : "无执行中语句");
        return cancelled;
    }

    /** 按请求/任务 executionId 精确取消。 */
    public boolean cancelExecution(String executionId) {
        boolean cancelled = StatementRegistry.cancelExecution(executionId);
        log.info("精确取消查询: executionId={}, 结果={}",
                executionId, cancelled ? "已发出取消" : "无执行中语句");
        return cancelled;
    }

    /**
     * 将导出结果逐行推送给消费者。JDBC 路径不构造 QueryResult，不在堆中保存完整结果集；
     * NoSQL 路径仍使用驱动有界结果，但同样受服务端硬上限约束。
     */
    public ExportSummary streamQueryForExport(String connectionId, String sql,
            String defaultDatabase, Integer limit, ExportConsumer consumer) throws Exception {
        validateExportSql(sql);
        int requested = limit != null && limit > 0 ? limit : MAX_EXPORT_ROWS;
        int queryLimit = Math.min(requested, MAX_EXPORT_ROWS);
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        long started = System.currentTimeMillis();

        long rows = withConnection(connectionId, active, defaultDatabase,
                "export-stream-" + UUID.randomUUID(), ac -> {
                    if (ac.connection instanceof Connection jdbc) {
                        return streamJdbc(jdbc, sql, queryLimit, consumer);
                    }
                    QueryResult bounded = ac.driver.executeQuery(ac.connection, sql, queryLimit);
                    List<String> columns = List.copyOf(bounded.getColumns());
                    requireUniqueColumnLabels(columns);
                    consumer.onColumns(columns);
                    long count = 0;
                    for (Map<String, Object> row : bounded.getRows()) {
                        if (count >= queryLimit) {
                            break;
                        }
                        consumer.onRow(row);
                        count++;
                    }
                    return count;
                });
        return new ExportSummary(rows, rows >= queryLimit,
                System.currentTimeMillis() - started);
    }

    private long streamJdbc(Connection jdbc, String sql, int limit,
            ExportConsumer consumer) throws Exception {
        try (Statement statement = jdbc.createStatement()) {
            statement.setMaxRows(limit);
            statement.setFetchSize(Math.min(1_000, limit));
            if (appProperties.getQueryTimeoutSeconds() > 0) {
                statement.setQueryTimeout(appProperties.getQueryTimeoutSeconds());
            }
            StatementRegistry.register(jdbc, statement);
            try {
                if (!statement.execute(sql)) {
                    throw new IllegalArgumentException("导出语句必须返回结果集");
                }
                try (ResultSet resultSet = statement.getResultSet()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    List<String> columns = new java.util.ArrayList<>(metadata.getColumnCount());
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        columns.add(metadata.getColumnLabel(index));
                    }
                    requireUniqueColumnLabels(columns);
                    consumer.onColumns(List.copyOf(columns));
                    long count = 0;
                    while (count < limit && resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= columns.size(); index++) {
                            row.put(columns.get(index - 1), resultSet.getObject(index));
                        }
                        consumer.onRow(row);
                        count++;
                    }
                    return count;
                }
            } finally {
                StatementRegistry.unregister(jdbc);
            }
        }
    }

    public int executeUpdate(String connectionId, String sql, String defaultDatabase) throws Exception {
        return executeUpdate(connectionId, sql, defaultDatabase, false);
    }

    public int executeUpdate(String connectionId, String sql, String defaultDatabase, boolean force)
            throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        if (active.driver.getCapabilities().isReadOnly()) {
            throw new IllegalStateException("此数据库为只读模式（OLAP 引擎），不允许执行 DML/DDL 操作");
        }

        String dbType = active.driver.getDriverInfo() != null
                ? active.driver.getDriverInfo().getDbType() : null;
        List<SqlReviewFinding> findings = sqlReviewService.review(sql, dbType);
        if (!force && sqlReviewService.hasBlocking(findings)) {
            log.info("SQL 审核拦截(update): connectionId={}, 命中={}条", connectionId, findings.size());
            throw new SqlReviewBlockedException(findings);
        }

        int affected;
        try {
            affected = withConnection(connectionId, active, defaultDatabase,
                    "update-" + UUID.randomUUID(),
                    ac -> ac.driver.executeUpdate(ac.connection, sql));
        } catch (Exception e) {
            queryHistoryService.record(connectionId, dbType, sql, 0L, 0L, false, safeMessage(e));
            throw e;
        }
        queryHistoryService.record(connectionId, dbType, sql, 0L, (long) affected, true, null);
        log.info("更新完成: connectionId={}, 影响行数={}", connectionId, affected);
        return affected;
    }

    /**
     * 在限时独占锁内完成切库、执行、恢复；恢复失败时主动销毁污染连接。
     */
    private <T> T withConnection(String connectionId, ConnectionService.ActiveConnection active,
            String defaultDatabase, String executionId, ConnectionWork<T> work) throws Exception {
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            StatementRegistry.begin(executionId);
            CatalogState catalog = CatalogState.none();
            Exception failure = null;
            try {
                catalog = switchDatabase(active, defaultDatabase);
                return work.execute(active);
            } catch (Exception e) {
                failure = e;
                throw e;
            } finally {
                try {
                    restoreCatalog(catalog);
                } catch (Exception restoreError) {
                    connectionService.disconnect(connectionId);
                    if (failure != null) {
                        failure.addSuppressed(restoreError);
                    } else {
                        throw new IllegalStateException("恢复数据库上下文失败，连接已关闭", restoreError);
                    }
                } finally {
                    StatementRegistry.end();
                }
            }
        }
    }

    private CatalogState switchDatabase(ConnectionService.ActiveConnection active, String database)
            throws Exception {
        if (database == null || database.isBlank() || !(active.connection instanceof Connection jdbc)) {
            return CatalogState.none();
        }
        String original = jdbc.getCatalog();
        if (original != null && original.equalsIgnoreCase(database.trim())) {
            return new CatalogState(jdbc, original, false);
        }
        jdbc.setCatalog(database.trim());
        return new CatalogState(jdbc, original, true);
    }

    private void restoreCatalog(CatalogState state) throws Exception {
        if (state.changed()) {
            state.connection().setCatalog(state.originalCatalog());
        }
    }

    /**
     * 只允许单条 SELECT/只读 CTE 导出；无法可靠判定时默认拒绝。
     */
    public static void validateExportSql(String sql) {
        SqlParseUtil.requireReadOnly(sql);
    }

    public static void requireUniqueColumnLabels(List<String> columns) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            if (!normalized.add(SqlParseUtil.normalizeQualifiedName(column))) {
                throw new IllegalArgumentException(
                        "结果包含大小写不敏感的重复列名，请为重复列设置唯一别名");
            }
        }
    }

    private QueryResult blockedResult(String sql, List<SqlReviewFinding> findings) {
        QueryResult blocked = new QueryResult();
        blocked.setSql(sql);
        blocked.setReviewBlocked(true);
        blocked.setReviewFindings(findings);
        return blocked;
    }

    private static String safeMessage(Exception e) {
        return e.getClass().getSimpleName();
    }

    public record ExportSummary(long rowCount, boolean truncated, long elapsedMs) {
    }

    public interface ExportConsumer {
        void onColumns(List<String> columns) throws Exception;

        void onRow(Map<String, Object> row) throws Exception;
    }

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T execute(ConnectionService.ActiveConnection active) throws Exception;
    }

    private record CatalogState(Connection connection, String originalCatalog, boolean changed) {
        static CatalogState none() {
            return new CatalogState(null, null, false);
        }
    }

    public static class SqlReviewBlockedException extends RuntimeException {
        private final transient List<SqlReviewFinding> findings;

        public SqlReviewBlockedException(List<SqlReviewFinding> findings) {
            super("SQL 审核拦截");
            this.findings = findings;
        }

        public List<SqlReviewFinding> getFindings() {
            return findings;
        }
    }
}
