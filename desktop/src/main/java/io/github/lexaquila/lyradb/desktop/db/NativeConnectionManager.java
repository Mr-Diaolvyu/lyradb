package io.github.lexaquila.lyradb.desktop.db;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.desktop.storage.DesktopStateStore;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.service.SqlParseUtil;
import io.github.lexaquila.lyradb.service.SqlReviewService;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 原生客户端数据库会话管理器。
 *
 * <p>桌面版直接持有驱动与连接对象，不启动本地 Web 服务。每个连接串行使用，
 * 避免目录切换、事务和并发语句互相污染。</p>
 */
public final class NativeConnectionManager implements AutoCloseable {

    private static final Set<String> REDIS_QUERY_COMMANDS = Set.of(
            "GET", "KEYS", "SCAN", "TYPE", "HGETALL", "LRANGE",
            "SMEMBERS", "ZRANGE", "STRLEN", "DBSIZE", "INFO", "TTL");

    private final DriverFactory driverFactory;
    private final DesktopStateStore stateStore;
    private final SqlReviewService sqlReviewService;
    private final AppProperties properties;
    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    public NativeConnectionManager(DriverFactory driverFactory,
            DesktopStateStore stateStore,
            SqlReviewService sqlReviewService,
            AppProperties properties) {
        this.driverFactory = driverFactory;
        this.stateStore = stateStore;
        this.sqlReviewService = sqlReviewService;
        this.properties = properties;
    }

    public boolean isConnected(String connectionId) {
        return sessions.containsKey(connectionId);
    }

    public DesktopConnection requireSaved(String connectionId) {
        return stateStore.findConnection(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("连接不存在: " + connectionId));
    }

    public void test(DesktopConnection definition) throws Exception {
        DatabaseDriver driver = driverFactory.createDriver(definition.getDbType());
        Object connection = driver.connect(definition.getParams());
        try {
            if (connection == null) {
                throw new IllegalStateException("驱动未返回有效连接");
            }
        } finally {
            driver.disconnect(connection);
        }
    }

    public synchronized void connect(String connectionId) throws Exception {
        if (sessions.containsKey(connectionId)) {
            return;
        }
        DesktopConnection definition = requireSaved(connectionId);
        DatabaseDriver driver =
                driverFactory.getOrCreateDriver(connectionId, definition.getDbType());
        Object connection = driver.connect(definition.getParams());
        if (connection == null) {
            throw new IllegalStateException("驱动未返回有效连接");
        }
        sessions.put(connectionId,
                new ActiveSession(definition.getDbType(), driver, connection));
    }

    public synchronized void disconnect(String connectionId) {
        ActiveSession removed = sessions.remove(connectionId);
        if (removed != null) {
            removed.lock.lock();
            try {
                removed.driver.disconnect(removed.connection);
            } finally {
                removed.lock.unlock();
            }
        }
        driverFactory.removeDriver(connectionId);
    }

    public List<TreeNode> tree(String connectionId, String parentPath) throws Exception {
        ActiveSession session = requireActive(connectionId);
        return withLock(session,
                () -> session.driver.getTreeNodes(session.connection, parentPath));
    }

    public List<ColumnMetadata> columns(String connectionId,
            String schemaName, String tableName) throws Exception {
        ActiveSession session = requireActive(connectionId);
        return withLock(session, () -> session.driver.getTableColumns(
                session.connection, schemaName, tableName));
    }

    public String ddl(String connectionId,
            String schemaName, String tableName) throws Exception {
        ActiveSession session = requireActive(connectionId);
        return withLock(session, () -> session.driver.getTableDDL(
                session.connection, schemaName, tableName));
    }

    public List<SqlReviewFinding> review(String connectionId, String sql) {
        return sqlReviewService.review(sql, requireActive(connectionId).dbType);
    }

    public ExecutionResult execute(String connectionId, String sql,
            int requestedLimit, boolean force, String executionId) throws Exception {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        ActiveSession session = requireActive(connectionId);
        List<SqlReviewFinding> findings = sqlReviewService.review(sql, session.dbType);
        if (!force && sqlReviewService.hasBlocking(findings)) {
            return ExecutionResult.blocked(sql, findings);
        }
        ensureSingleCommand(sql, session.dbType);
        int limit = Math.max(1, Math.min(requestedLimit, properties.getMaxQueryRows()));
        String safeExecutionId = executionId == null || executionId.isBlank()
                ? "desktop-" + UUID.randomUUID() : executionId;

        return withLock(session, () -> {
            StatementRegistry.begin(safeExecutionId);
            try {
                if (isQuery(session.dbType, sql)) {
                    QueryResult result =
                            session.driver.executeQuery(session.connection, sql, limit);
                    result.setReviewFindings(findings);
                    return ExecutionResult.query(result, findings);
                }
                if (session.driver.getCapabilities().isReadOnly()) {
                    throw new IllegalStateException("此数据库连接为只读模式");
                }
                int affected = session.driver.executeUpdate(session.connection, sql);
                return ExecutionResult.update(affected, findings);
            } finally {
                StatementRegistry.end();
            }
        });
    }

    public boolean cancel(String executionId) {
        return StatementRegistry.cancelExecution(executionId);
    }

    public void beginTransaction(String connectionId) throws Exception {
        ActiveSession session = requireActive(connectionId);
        withLock(session, () -> {
            Connection jdbc = requireJdbc(session);
            if (jdbc.getAutoCommit()) {
                jdbc.setAutoCommit(false);
            }
            return null;
        });
    }

    public void commit(String connectionId) throws Exception {
        ActiveSession session = requireActive(connectionId);
        withLock(session, () -> {
            Connection jdbc = requireJdbc(session);
            if (!jdbc.getAutoCommit()) {
                jdbc.commit();
                jdbc.setAutoCommit(true);
            }
            return null;
        });
    }

    public void rollback(String connectionId) throws Exception {
        ActiveSession session = requireActive(connectionId);
        withLock(session, () -> {
            Connection jdbc = requireJdbc(session);
            if (!jdbc.getAutoCommit()) {
                jdbc.rollback();
                jdbc.setAutoCommit(true);
            }
            return null;
        });
    }

    public boolean inTransaction(String connectionId) {
        ActiveSession session = requireActive(connectionId);
        if (!(session.connection instanceof Connection jdbc)) {
            return false;
        }
        session.lock.lock();
        try {
            return !jdbc.getAutoCommit();
        } catch (Exception ignored) {
            return false;
        } finally {
            session.lock.unlock();
        }
    }

    /**
     * 在连接级会话锁内执行 JDBC 元数据操作。
     *
     * <p>调用方不得保存或向外返回连接对象；该 API 只用于无法通过统一驱动
     * 元数据接口表达的短时 JDBC 操作。</p>
     */
    public <T> T withLockedJdbcConnection(
            String connectionId, JdbcOperation<T> operation) throws Exception {
        Objects.requireNonNull(operation, "JDBC 操作不能为空");
        ActiveSession session = requireActive(connectionId);
        return withLock(session, () -> {
            if (!(session.connection instanceof Connection jdbc)) {
                throw new IllegalStateException("当前数据库不是 JDBC 连接");
            }
            return operation.apply(jdbc);
        });
    }

    public String dbType(String connectionId) {
        return requireActive(connectionId).dbType;
    }

    @Override
    public void close() {
        for (String connectionId : List.copyOf(sessions.keySet())) {
            disconnect(connectionId);
        }
    }

    private ActiveSession requireActive(String connectionId) {
        ActiveSession session = sessions.get(connectionId);
        if (session == null) {
            throw new IllegalStateException("连接尚未打开");
        }
        return session;
    }

    private static Connection requireJdbc(ActiveSession session) {
        if (!(session.connection instanceof Connection jdbc)) {
            throw new IllegalStateException("当前数据库不支持 JDBC 事务");
        }
        if (!session.driver.getCapabilities().isSupportsTransaction()) {
            throw new IllegalStateException("当前数据库不支持事务");
        }
        return jdbc;
    }

    private static boolean isQuery(String dbType, String commandText) {
        String normalized = commandText.trim();
        if ("MONGODB".equalsIgnoreCase(dbType)) {
            // MongoDB 写操作使用 JSON DSL；db.collection / db/collection 是读取。
            return !normalized.startsWith("{");
        }

        String firstWord = SqlParseUtil.firstWord(
                normalized.toUpperCase(Locale.ROOT)).replace(";", "");
        if ("REDIS".equalsIgnoreCase(dbType)) {
            return REDIS_QUERY_COMMANDS.contains(firstWord);
        }

        return switch (firstWord) {
            case "SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "DESC",
                    "EXPLAIN", "PRAGMA" -> true;
            default -> false;
        };
    }

    private static void ensureSingleCommand(String sql, String dbType) {
        if ("MONGODB".equalsIgnoreCase(dbType) || "REDIS".equalsIgnoreCase(dbType)) {
            return;
        }
        String normalized = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ")
                .replaceAll("'(?:''|[^'])*'", "''");
        int commands = 0;
        for (String part : normalized.split(";")) {
            if (!part.isBlank()) {
                commands++;
            }
        }
        if (commands > 1) {
            throw new IllegalArgumentException("桌面工作台一次只执行一条 SQL，请选中后分次执行");
        }
    }

    private static <T> T withLock(ActiveSession session, CheckedSupplier<T> supplier)
            throws Exception {
        session.lock.lockInterruptibly();
        try {
            return supplier.get();
        } finally {
            session.lock.unlock();
        }
    }

    private static final class ActiveSession {
        private final String dbType;
        private final DatabaseDriver driver;
        private final Object connection;
        private final ReentrantLock lock = new ReentrantLock();

        private ActiveSession(String dbType, DatabaseDriver driver, Object connection) {
            this.dbType = dbType;
            this.driver = driver;
            this.connection = connection;
        }
    }

    @FunctionalInterface
    public interface JdbcOperation<T> {
        T apply(Connection connection) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public record ExecutionResult(QueryResult queryResult,
                                  Integer affectedRows,
                                  List<SqlReviewFinding> findings,
                                  boolean blocked) {
        public static ExecutionResult query(QueryResult result,
                List<SqlReviewFinding> findings) {
            return new ExecutionResult(result, null, List.copyOf(findings), false);
        }

        public static ExecutionResult update(int affected,
                List<SqlReviewFinding> findings) {
            return new ExecutionResult(null, affected, List.copyOf(findings), false);
        }

        public static ExecutionResult blocked(String sql,
                List<SqlReviewFinding> findings) {
            QueryResult result = new QueryResult();
            result.setSql(sql);
            result.setReviewBlocked(true);
            result.setReviewFindings(findings);
            return new ExecutionResult(result, null, List.copyOf(findings), true);
        }
    }
}
