package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询执行服务
 *
 * <p>
 * 负责SQL查询的执行和结果返回。通过DatabaseDriver接口统一适配9种数据库的查询执行。
 * </p>
 *
 * <p>
 * 对于JDBC类型数据库：执行标准SQL，返回ResultSet
 * </p>
 * <p>
 * 对于MongoDB：执行find查询，返回文档列表
 * </p>
 * <p>
 * 对于Redis：执行GET/SCAN等命令，返回Key-Value
 * </p>
 *
 * <p>
 * 安全控制：根据DriverCapability.readOnly判断是否允许DML操作，
 * OLAP数据库（如MaxCompute）声明为只读模式，自动拒绝DML请求。
 * </p>
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

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

    /**
     * 执行查询SQL
     *
     * @param connectionId    连接ID
     * @param sql             SQL语句
     * @param defaultDatabase 默认数据库（可选，用于切换查询上下文）
     * @return 查询结果
     */
    public QueryResult executeQuery(String connectionId, String sql, String defaultDatabase) throws Exception {
        return executeQuery(connectionId, sql, defaultDatabase, false);
    }

    /**
     * 执行查询SQL（含 SQL 审核前置）
     *
     * @param force 用户确认"仍要执行"后跳过拦截（逃生门）
     */
    public QueryResult executeQuery(String connectionId, String sql, String defaultDatabase, boolean force)
            throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        int limit = appProperties.getMaxQueryRows();

        String dbType = active.driver.getDriverInfo() != null ? active.driver.getDriverInfo().getDbType() : null;

        // SQL 审核前置：HIGH/MEDIUM 拦截（force 逃生），LOW 随结果附带提醒
        List<SqlReviewFinding> findings = sqlReviewService.review(sql, dbType);
        if (!force && sqlReviewService.hasBlocking(findings)) {
            log.info("SQL审核拦截: connectionId={}, 命中={}条", connectionId, findings.size());
            return blockedResult(sql, findings);
        }

        // 如果指定了默认数据库，尝试切换
        if (defaultDatabase != null && !defaultDatabase.trim().isEmpty()) {
            switchDatabase(active, defaultDatabase);
        }

        log.info("执行查询: connectionId={}, sql={}", connectionId,
                sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

        QueryResult result;
        try {
            result = active.driver.executeQuery(active.connection, sql, limit);
        } catch (Exception e) {
            queryHistoryService.record(connectionId, dbType, sql, 0L, 0L, false, e.getMessage());
            throw e;
        }
        log.info("查询完成: 耗时={}ms, 行数={}", result.getElapsedMs(), result.getTotalRows());

        // 记录历史：成功则正常记录，结果含 error 列则标记失败
        boolean hasError = result.getColumns() != null && result.getColumns().contains("error")
                && result.getTotalRows() > 0;
        String errMsg = null;
        if (hasError && result.getRows() != null && !result.getRows().isEmpty()) {
            Object errVal = result.getRows().get(0).get("error");
            if (errVal != null) {
                errMsg = errVal.toString();
            }
        }
        queryHistoryService.record(connectionId, dbType, sql,
                result.getElapsedMs(),
                result.getTotalRows(),
                !hasError, errMsg);

        // LOW 级提醒随结果返回（不阻断）
        if (!findings.isEmpty()) {
            result.setReviewFindings(findings);
        }

        return result;
    }

    /**
     * 取消指定连接上正在执行的查询
     *
     * <p>
     * 仅对 JDBC 类驱动有效（通过 Statement.cancel 中断）；
     * NoSQL 命令为短耗时操作，无需取消。
     * </p>
     *
     * @param connectionId 连接ID
     * @return true 表示找到执行中语句并已发出取消请求
     */
    public boolean cancelQuery(String connectionId) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        boolean cancelled = StatementRegistry.cancel(active.connection);
        log.info("取消查询: connectionId={}, 结果={}", connectionId, cancelled ? "已发出取消" : "无执行中语句");
        return cancelled;
    }

    /**
     * 执行查询SQL用于导出（支持自定义行数限制）
     *
     * @param connectionId    连接ID
     * @param sql             SQL语句
     * @param defaultDatabase 默认数据库（可选）
     * @param limit           最大返回行数（为null或<=0时使用默认限制）
     * @return 查询结果
     */
    public QueryResult executeQueryForExport(String connectionId, String sql, String defaultDatabase, Integer limit)
            throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        int queryLimit = (limit != null && limit > 0) ? limit : appProperties.getMaxQueryRows();

        if (defaultDatabase != null && !defaultDatabase.trim().isEmpty()) {
            switchDatabase(active, defaultDatabase);
        }

        log.info("导出查询: connectionId={}, sql={}", connectionId,
                sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

        QueryResult result = active.driver.executeQuery(active.connection, sql, queryLimit);
        log.info("导出查询完成: 耗时={}ms, 行数={}", result.getElapsedMs(), result.getTotalRows());

        return result;
    }

    /**
     * 切换当前连接的默认数据库
     */
    private void switchDatabase(ConnectionService.ActiveConnection active, String database) {
        try {
            Object conn = active.connection;
            if (conn instanceof java.sql.Connection jdbcConn) {
                String currentCatalog = jdbcConn.getCatalog();
                if (currentCatalog == null || !currentCatalog.equalsIgnoreCase(database)) {
                    jdbcConn.setCatalog(database);
                    log.info("已切换数据库: {}", database);
                }
            }
        } catch (Exception e) {
            log.warn("切换数据库失败: {} - {}", database, e.getMessage());
        }
    }

    /**
     * 执行更新/DDL语句
     *
     * @param connectionId    连接ID
     * @param sql             SQL语句
     * @param defaultDatabase 默认数据库（可选，用于切换查询上下文）
     * @return 影响行数
     */
    public int executeUpdate(String connectionId, String sql, String defaultDatabase) throws Exception {
        return executeUpdate(connectionId, sql, defaultDatabase, false);
    }

    /**
     * 执行更新/DDL语句（含 SQL 审核前置）
     *
     * @param force 用户确认"仍要执行"后跳过拦截（逃生门）
     */
    public int executeUpdate(String connectionId, String sql, String defaultDatabase, boolean force) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);

        // 只读数据库不允许DML
        if (active.driver.getCapabilities().isReadOnly()) {
            throw new RuntimeException("此数据库为只读模式（OLAP引擎），不允许执行DML/DDL操作");
        }

        String dbType = active.driver.getDriverInfo() != null ? active.driver.getDriverInfo().getDbType() : null;

        // SQL 审核前置：命中拦截级规则且未确认时抛出审核异常（由 Controller 转换为拦截响应）
        List<SqlReviewFinding> findings = sqlReviewService.review(sql, dbType);
        if (!force && sqlReviewService.hasBlocking(findings)) {
            log.info("SQL审核拦截(update): connectionId={}, 命中={}条", connectionId, findings.size());
            throw new SqlReviewBlockedException(findings);
        }

        if (defaultDatabase != null && !defaultDatabase.trim().isEmpty()) {
            switchDatabase(active, defaultDatabase);
        }

        log.info("执行更新: connectionId={}, sql={}", connectionId,
                sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);

        int affected;
        try {
            affected = active.driver.executeUpdate(active.connection, sql);
        } catch (Exception e) {
            queryHistoryService.record(connectionId, dbType, sql, 0L, 0L, false, e.getMessage());
            throw e;
        }
        log.info("更新完成: 影响行数={}", affected);
        queryHistoryService.record(connectionId, dbType, sql, 0L, (long) affected, true, null);

        return affected;
    }

    /** 构造审核拦截结果（不执行 SQL） */
    private QueryResult blockedResult(String sql, List<SqlReviewFinding> findings) {
        QueryResult blocked = new QueryResult();
        blocked.setSql(sql);
        blocked.setReviewBlocked(true);
        blocked.setReviewFindings(findings);
        return blocked;
    }

    /**
     * SQL 审核拦截异常（携带命中规则，供 Controller 返回结构化拦截响应）
     */
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
