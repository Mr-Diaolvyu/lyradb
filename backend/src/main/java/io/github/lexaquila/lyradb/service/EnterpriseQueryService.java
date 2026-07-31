



package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;
import io.github.lexaquila.lyradb.model.dto.TableInspection;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 企业查询治理链：工作空间授权、AST 校验、审批、独占连接、脱敏和审计。
 */
@Service
public class EnterpriseQueryService {

    private static final Logger log =
            LoggerFactory.getLogger(EnterpriseQueryService.class);
    private static final Pattern CONCRETE_NAMESPACE_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;
    private final SqlReviewService sqlReviewService;
    private final ApprovalService approvalService;
    private final MaskingService maskingService;
    private final AppProperties appProperties;

    public EnterpriseQueryService(GrantService grantService,
                                  DataSourceService dataSourceService,
                                  AuditService auditService,
                                  SecurityUtil securityUtil,
                                  SqlReviewService sqlReviewService,
                                  ApprovalService approvalService,
                                  MaskingService maskingService,
                                  AppProperties appProperties) {
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
        this.sqlReviewService = sqlReviewService;
        this.approvalService = approvalService;
        this.maskingService = maskingService;
        this.appProperties = appProperties;
    }

    public QueryResult executeQuery(String grantedSourceName, String sql,
                                    String defaultDatabase) throws Exception {
        AccessContext access = requireAccess(grantedSourceName);
        User user = access.user();
        Grant grant = access.grant();
        DataSource dataSource = access.dataSource();

        long start = System.currentTimeMillis();
        String approvalId = null;
        SqlParseUtil.Analysis analysis = null;
        QueryResult result;
        List<SqlReviewFinding> findings;
        boolean[] externalDmlDispatched = {false};
        try {
            analysis = authorize(grant, sql, defaultDatabase);
            findings = sqlReviewService.review(sql, dataSource.getDbType());
            if (analysis.type() == SqlParseUtil.StatementType.DML
                    || sqlReviewService.hasHigh(findings)) {
                approvalId = requireAndClaimApproval(
                        grant, user, sql, defaultDatabase, findings);
            }
            if (analysis.type() == SqlParseUtil.StatementType.DML) {
                // 目标数据库与本地审计库无法形成同一事务；先持久化执行意图，
                // 审计不可用时失败关闭，绝不触发外部 DML。
                audit(grant, user, dataSource.getDbType(), "DML_START",
                        sql, 0, 0, true, null, approvalId);
            }

            ConnectionService.ActiveConnection active =
                    dataSourceService.resolveActiveConnection(
                            grant.getDataSourceId());
            int limit = Math.max(1, grant.getMaxRowsPerQuery());
            try (ConnectionService.ActiveConnection.Lease ignored =
                         active.acquire()) {
                result = executeMaterialized(
                        active, grant.getDataSourceId(),
                        dataSource.getDbType(), sql, limit,
                        defaultDatabase, analysis,
                        externalDmlDispatched);
            }
            result.setSql(sql);
            result.setElapsedMs(System.currentTimeMillis() - start);
            if (analysis.type() == SqlParseUtil.StatementType.READ) {
                QueryService.requireUniqueColumnLabels(result.getColumns());
                maskingService.applyMasking(
                        result, grant.getWorkspaceId(),
                        grant.getDataSourceId(), analysis);
            }
            if (!findings.isEmpty()) {
                result.setReviewFindings(findings);
            }
        } catch (Exception exception) {
            long elapsed = System.currentTimeMillis() - start;
            if (approvalId != null) {
                try {
                    if (analysis != null
                            && analysis.type() == SqlParseUtil.StatementType.DML
                            && externalDmlDispatched[0]) {
                        approvalService.markExecutionUnknown(
                                approvalId,
                                "DML 已发送至外部数据库但未确认结果，禁止自动重试，请人工核验");
                    } else {
                        approvalService.markExecutionResult(
                                approvalId, false,
                                "执行失败: " + safeMessage(exception));
                    }
                } catch (Exception markFailure) {
                    log.error("审批单失败状态写入失败: {}",
                            markFailure.getMessage(), markFailure);
                }
            }
            String failedOperation = analysis != null
                    && analysis.type() == SqlParseUtil.StatementType.DML
                    ? "DML" : "QUERY";
            String auditApprovalId = approvalId;
            if (auditApprovalId == null
                    && exception instanceof ApprovalRequiredException approvalRequired) {
                auditApprovalId = approvalRequired.getApprovalRequestId();
            }
            audit(grant, user, dataSource.getDbType(), failedOperation,
                    sql, 0, elapsed, false,
                    safeMessage(exception), auditApprovalId);
            throw exception;
        }

        long elapsed = System.currentTimeMillis() - start;
        if (approvalId != null) {
            approvalService.markExecutionResult(
                    approvalId, true, "SQL 已执行，耗时 " + elapsed + "ms");
        }
        String operation = analysis.type() == SqlParseUtil.StatementType.READ
                ? "QUERY" : "DML";
        try {
            audit(grant, user, dataSource.getDbType(), operation,
                    sql, result.getTotalRows(), elapsed, true, null, approvalId);
        } catch (RuntimeException finalAuditFailure) {
            if (analysis.type() != SqlParseUtil.StatementType.DML) {
                throw finalAuditFailure;
            }
            log.error("DML 已成功且审批终态为 DONE，但最终汇总审计失败；"
                            + "DML_START 仍作为执行证据: approvalId={}",
                    approvalId, finalAuditFailure);
        }
        return result;
    }

    /**
     * 企业版表工作台：先执行 Grant 白名单校验，再复用企业查询链路完成
     * 预览、脱敏与审计；元数据同样只在授权通过后读取。
     */
    public TableInspection inspectTable(
            String grantedSourceName,
            String schema,
            String table,
            String objectType,
            int requestedLimit) throws Exception {
        AccessContext access = requireAccess(grantedSourceName);
        Grant grant = access.grant();
        authorizeTableInspection(grant, schema, table);

        int limit = Math.max(1, Math.min(200,
                Math.min(requestedLimit,
                        Math.max(1, grant.getMaxRowsPerQuery()))));
        TableInspection inspection = new TableInspection();
        inspection.setSchema(schema);
        inspection.setTable(table);
        inspection.setObjectType(
                objectType == null || objectType.isBlank()
                        ? "TABLE" : objectType.toUpperCase(Locale.ROOT));

        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(
                        grant.getDataSourceId());
        String previewSql;
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            previewSql = active.driver.buildTablePreviewSql(
                    active.connection, schema, table, limit);
        }
        try {
            inspection.setPreview(executeQuery(
                    grantedSourceName, previewSql, null));
        } catch (Exception exception) {
            inspection.addError("preview", safeMessage(exception));
        }

        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            try {
                inspection.setColumns(active.driver.getTableColumns(
                        active.connection, schema, table));
            } catch (Exception exception) {
                inspection.addError("columns", safeMessage(exception));
            }
            try {
                inspection.setConstraints(active.driver.getTableConstraints(
                        active.connection, schema, table));
            } catch (Exception exception) {
                inspection.addError(
                        "constraints", safeMessage(exception));
            }
            try {
                inspection.setDdl(active.driver.getTableDDL(
                        active.connection, schema, table));
            } catch (Exception exception) {
                inspection.addError("ddl", safeMessage(exception));
            }
        }
        return inspection;
    }

    private void authorizeTableInspection(
            Grant grant, String schema, String table) {
        if (schema == null || schema.isBlank()
                || table == null || table.isBlank()) {
            throw new IllegalArgumentException(
                    "企业表工作台必须指定 Schema 和表名");
        }
        String qualified = schema.trim() + "." + table.trim();
        Set<String> allowedSchemas =
                SqlParseUtil.splitCsv(grant.getAllowedSchemas());
        Set<String> allowedTables =
                SqlParseUtil.splitCsv(grant.getAllowedTables());
        Set<String> blockedTables =
                SqlParseUtil.splitCsv(grant.getBlockedTables());
        if (!SqlParseUtil.matchAny(schema, allowedSchemas)) {
            throw new RuntimeException(
                    "Schema 不在授权范围内: " + schema);
        }
        if (SqlParseUtil.matchAny(qualified, blockedTables)) {
            throw new RuntimeException(
                    "表在黑名单中，禁止访问: " + qualified);
        }
        if (!SqlParseUtil.matchAny(qualified, allowedTables)) {
            throw new RuntimeException(
                    "表不在授权白名单内: " + qualified);
        }
    }

    /**
     * 企业导出专用流式执行。只接受 JDBC 与只读 AST，在持有连接独占租约期间
     * 逐行读取 ResultSet、逐行脱敏并推送给响应写入器，不构造完整 QueryResult。
     */
    public ExportSummary streamExport(Grant grant, String sql,
                                      String defaultDatabase,
                                      ExportConsumer consumer)
            throws Exception {
        if (grant == null || grant.getId() == null) {
            throw new IllegalArgumentException("导出必须使用入口已验证的授权上下文");
        }
        SqlParseUtil.Analysis analysis =
                authorizeReadOnly(grant, sql, defaultDatabase);
        int limit = Math.min(QueryService.MAX_EXPORT_ROWS,
                Math.max(1, grant.getMaxRowsPerQuery()));
        DataSource dataSource =
                dataSourceService.getEntity(grant.getDataSourceId());
        if (!grant.getWorkspaceId().equals(dataSource.getWorkspaceId())) {
            throw new IllegalStateException(
                    "授权与真实数据源工作空间不一致");
        }
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(
                        grant.getDataSourceId());
        if (!(active.connection instanceof Connection jdbc)) {
            throw new IllegalArgumentException(
                    "企业流式导出当前仅支持 JDBC 数据源");
        }

        long started = System.currentTimeMillis();
        long rows;
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            rows = streamJdbc(
                    jdbc, grant, dataSource.getDbType(),
                    analysis, sql, defaultDatabase, limit, consumer);
        }
        return new ExportSummary(
                rows, rows >= limit, System.currentTimeMillis() - started);
    }

    /**
     * 导出与普通查询共用同一套 AST 与 Grant 资源授权。
     */
    public SqlParseUtil.Analysis authorizeReadOnly(
            Grant grant, String sql, String defaultDatabase) {
        SqlParseUtil.Analysis analysis = SqlParseUtil.requireEnterpriseReadOnly(sql);
        authorizeResources(grant, analysis, defaultDatabase);
        return analysis;
    }

    private AccessContext requireAccess(String grantedSourceName) {
        User user = securityUtil.requireCurrentUser();
        String currentWorkspace = securityUtil.requireCurrentWorkspace();
        Grant grant = grantService.resolveForUser(
                user.getId(), currentWorkspace, grantedSourceName);
        if (!currentWorkspace.equals(grant.getWorkspaceId())) {
            throw new RuntimeException("逻辑数据源不属于当前工作空间");
        }
        DataSource dataSource =
                dataSourceService.getEntity(grant.getDataSourceId());
        if (!currentWorkspace.equals(dataSource.getWorkspaceId())) {
            throw new RuntimeException("授权与真实数据源工作空间不一致");
        }
        return new AccessContext(user, grant, dataSource);
    }

    private SqlParseUtil.Analysis authorize(
            Grant grant, String sql, String defaultDatabase) {
        SqlParseUtil.Analysis analysis = SqlParseUtil.analyzeEnterprise(sql);
        if (analysis.type() == SqlParseUtil.StatementType.DML
                && !"DML_ALLOWED".equalsIgnoreCase(
                        grant.getSqlCapability())) {
            throw new RuntimeException("当前授权为只读，不允许 DML");
        }
        authorizeResources(grant, analysis, defaultDatabase);
        return analysis;
    }

    private void authorizeResources(Grant grant,
                                    SqlParseUtil.Analysis analysis,
                                    String defaultDatabase) {
        Set<String> allowedTables =
                SqlParseUtil.splitCsv(grant.getAllowedTables());
        Set<String> blockedTables =
                SqlParseUtil.splitCsv(grant.getBlockedTables());
        Set<String> allowedSchemas =
                SqlParseUtil.splitCsv(grant.getAllowedSchemas());

        if (analysis.type() == SqlParseUtil.StatementType.READ
                && analysis.tables().isEmpty()) {
            throw new RuntimeException(
                    "企业查询必须引用已授权物理表");
        }
        if (allowedTables.isEmpty()) {
            throw new RuntimeException(
                    "授权未配置表白名单，默认拒绝访问任何表");
        }
        if (allowedSchemas.isEmpty()) {
            throw new RuntimeException(
                    "企业物理表授权必须显式配置 Schema 白名单");
        }
        if (allowedTables.stream().anyMatch(table -> !table.contains("."))) {
            throw new RuntimeException(
                    "企业表白名单必须使用 Schema.Table 完整限定名");
        }
        for (String table : analysis.tables()) {
            if (SqlParseUtil.matchAny(table, blockedTables)) {
                throw new RuntimeException(
                        "表在黑名单中，禁止访问: " + table);
            }
            if (!SqlParseUtil.matchAny(table, allowedTables)) {
                throw new RuntimeException(
                        "表不在授权白名单内: " + table);
            }
            String schema = SqlParseUtil.schemaOf(table);
            if (schema == null) {
                throw new RuntimeException(
                        "企业 SQL 必须使用 Schema.Table 完整限定物理表");
            }
            if (!SqlParseUtil.matchAny(schema, allowedSchemas)) {
                throw new RuntimeException(
                        "Schema 不在授权范围内: " + schema);
            }
        }
        if (defaultDatabase != null && !defaultDatabase.isBlank()) {
            requireConcreteNamespace(defaultDatabase);
            if (allowedSchemas.isEmpty()
                    || !SqlParseUtil.matchAny(
                            defaultDatabase, allowedSchemas)) {
                throw new RuntimeException(
                        "默认数据库/Schema 未被显式授权");
            }
        }
    }

    private String requireAndClaimApproval(
            Grant grant, User user, String sql, String defaultDatabase,
            List<SqlReviewFinding> findings) {
        java.util.Optional<ApprovalRequest> matching =
                approvalService.findActiveMatching(
                        user, grant, "DANGEROUS_SQL",
                        sql, null, defaultDatabase);
        if (matching.isPresent()) {
            ApprovalRequest approval = matching.get();
            if ("APPROVED".equals(approval.getStatus())) {
                approvalService.claimForExecution(
                        approval.getId(), user, grant,
                        "DANGEROUS_SQL", sql, null, defaultDatabase);
                return approval.getId();
            }
            throw new ApprovalRequiredException(
                    approval.getId(), "PENDING");
        }

        String reason = findings.stream()
                .filter(finding ->
                        "HIGH".equals(finding.getSeverity()))
                .map(SqlReviewFinding::getMessage)
                .reduce((left, right) -> left + "；" + right)
                .orElse("企业 DML 执行必须审批");
        ApprovalRequest created = approvalService.createDangerousSql(
                grant, user, sql, defaultDatabase,
                "SQL 审核命中：" + reason);
        throw new ApprovalRequiredException(
                created.getId(), "CREATED");
    }

    private QueryResult executeMaterialized(
            ConnectionService.ActiveConnection active,
            String dataSourceId, String dbType,
            String sql, int limit,
            String defaultDatabase,
            SqlParseUtil.Analysis analysis,
            boolean[] externalDmlDispatched) throws Exception {
        StatementRegistry.begin("ent-query-" + UUID.randomUUID());
        ConnectionNamespaceState namespace =
                ConnectionNamespaceState.none();
        ReadOnlyState readOnlyState = ReadOnlyState.none();
        Exception failure = null;
        try {
            if (analysis.type() == SqlParseUtil.StatementType.READ) {
                readOnlyState = ReadOnlyState.capture(active.connection);
                readOnlyState.enable();
            }
            namespace = switchNamespace(
                    active.connection, defaultDatabase,
                    dbType, dataSourceId);
            if (analysis.type() == SqlParseUtil.StatementType.READ) {
                return active.driver.executeQuery(
                        active.connection, sql, limit);
            }
            externalDmlDispatched[0] = true;
            int affectedRows =
                    active.driver.executeUpdate(active.connection, sql);
            return updateResult(sql, affectedRows);
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                restoreConnectionState(
                        namespace, readOnlyState, dataSourceId, failure);
            } finally {
                StatementRegistry.end();
            }
        }
    }

    private long streamJdbc(Connection jdbc, Grant grant,
                            String dbType,
                            SqlParseUtil.Analysis analysis, String sql,
                            String defaultDatabase, int limit,
                            ExportConsumer consumer) throws Exception {
        StatementRegistry.begin("ent-export-" + UUID.randomUUID());
        ConnectionNamespaceState namespace =
                ConnectionNamespaceState.none();
        ReadOnlyState readOnlyState = ReadOnlyState.none();
        Exception failure = null;
        try {
            readOnlyState = ReadOnlyState.capture(jdbc);
            readOnlyState.enable();
            namespace = switchNamespace(
                    jdbc, defaultDatabase, dbType,
                    grant.getDataSourceId());
            try (Statement statement = jdbc.createStatement()) {
                statement.setMaxRows(limit);
                statement.setFetchSize(Math.min(1_000, limit));
                if (appProperties.getQueryTimeoutSeconds() > 0) {
                    statement.setQueryTimeout(
                            appProperties.getQueryTimeoutSeconds());
                }
                StatementRegistry.register(jdbc, statement);
                try {
                    if (!statement.execute(sql)) {
                        throw new IllegalArgumentException(
                                "导出语句必须返回结果集");
                    }
                    try (ResultSet resultSet =
                                 statement.getResultSet()) {
                        ResultSetMetaData metadata =
                                resultSet.getMetaData();
                        List<String> columns = new ArrayList<>(
                                metadata.getColumnCount());
                        for (int index = 1;
                             index <= metadata.getColumnCount();
                             index++) {
                            columns.add(
                                    metadata.getColumnLabel(index));
                        }
                        QueryService.requireUniqueColumnLabels(columns);
                        MaskingService.MaskingPlan maskingPlan =
                                maskingService.preparePlan(
                                        grant.getWorkspaceId(),
                                        grant.getDataSourceId(),
                                        analysis, columns);
                        consumer.onColumns(List.copyOf(columns));
                        long count = 0;
                        while (count < limit
                                && resultSet.next()) {
                            Map<String, Object> row =
                                    new LinkedHashMap<>();
                            for (int index = 1;
                                 index <= columns.size();
                                 index++) {
                                row.put(columns.get(index - 1),
                                        resultSet.getObject(index));
                            }
                            maskingPlan.apply(row);
                            consumer.onRow(row);
                            count++;
                        }
                        return count;
                    }
                } finally {
                    StatementRegistry.unregister(jdbc);
                }
            }
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                restoreConnectionState(
                        namespace, readOnlyState,
                        grant.getDataSourceId(), failure);
            } finally {
                StatementRegistry.end();
            }
        }
    }

    ConnectionNamespaceState switchNamespace(
            Object connection, String defaultDatabase,
            String dbType, String dataSourceId) throws Exception {
        if (defaultDatabase == null || defaultDatabase.isBlank()) {
            return ConnectionNamespaceState.none();
        }
        if (!(connection instanceof Connection jdbc)) {
            throw new IllegalArgumentException(
                    "默认数据库/Schema 切换仅支持 JDBC 数据源");
        }
        String target = requireConcreteNamespace(defaultDatabase);
        try {
            NamespaceAttribute attribute =
                    NamespaceAttribute.forDatabaseType(dbType);
            String original = attribute.read(jdbc);
            if (sameNamespace(original, target)) {
                return new ConnectionNamespaceState(
                        jdbc, attribute, original, false);
            }
            attribute.write(jdbc, target);
            String actual = attribute.read(jdbc);
            if (!sameNamespace(actual, target)) {
                throw new IllegalStateException(
                        "JDBC 驱动未确认默认数据库/Schema 切换");
            }
            return new ConnectionNamespaceState(
                    jdbc, attribute, original, true);
        } catch (Exception exception) {
            try {
                dataSourceService.disconnect(dataSourceId);
            } catch (Exception disconnectFailure) {
                exception.addSuppressed(disconnectFailure);
            }
            throw new IllegalStateException(
                    "默认数据库/Schema 切换失败，企业数据源连接已关闭",
                    exception);
        }
    }

    private void restoreConnectionState(
            ConnectionNamespaceState namespace,
            ReadOnlyState readOnlyState,
            String dataSourceId, Exception executionFailure)
            throws Exception {
        Exception restoreFailure = null;
        try {
            namespace.restore();
        } catch (Exception exception) {
            restoreFailure = exception;
        }
        try {
            readOnlyState.restore();
        } catch (Exception exception) {
            if (restoreFailure == null) {
                restoreFailure = exception;
            } else {
                restoreFailure.addSuppressed(exception);
            }
        }
        if (restoreFailure == null) {
            return;
        }
        try {
            dataSourceService.disconnect(dataSourceId);
        } catch (Exception disconnectFailure) {
            restoreFailure.addSuppressed(disconnectFailure);
        }
        if (executionFailure != null) {
            executionFailure.addSuppressed(restoreFailure);
            return;
        }
        throw new IllegalStateException(
                "恢复数据库上下文失败，企业数据源连接已关闭",
                restoreFailure);
    }

    private static QueryResult updateResult(
            String sql, int affectedRows) {
        QueryResult result = new QueryResult();
        result.setSql(sql);
        result.addColumn("affectedRows");
        Map<String, Object> row = result.newRow();
        row.put("affectedRows", affectedRows);
        result.addRow(row);
        result.setTotalRows(Math.max(0, affectedRows));
        return result;
    }

    private void audit(Grant grant, User user, String dbType,
                       String operation, String sql, long rows,
                       long elapsed, boolean success, String error,
                       String approvalId) {
        auditService.record(
                grant.getWorkspaceId(), user.getId(),
                user.getUsername(),
                securityUtil.effectiveRoles(
                                grant.getWorkspaceId())
                        .stream().findFirst().orElse("ANALYST"),
                grant.getDataSourceId(),
                grant.getGrantedSourceName(), dbType,
                operation, operation, sql, 0, rows, elapsed,
                success, error, approvalId);
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

    public record ExportSummary(
            long rowCount, boolean truncated, long elapsedMs) {
    }

    public interface ExportConsumer {
        void onColumns(List<String> columns) throws Exception;

        void onRow(Map<String, Object> row) throws Exception;
    }

    private record AccessContext(
            User user, Grant grant, DataSource dataSource) {
    }

    private static final class ReadOnlyState {
        private final Connection connection;
        private final boolean originalReadOnly;
        private boolean changed;

        private ReadOnlyState(
                Connection connection, boolean originalReadOnly) {
            this.connection = connection;
            this.originalReadOnly = originalReadOnly;
        }

        private static ReadOnlyState capture(Object connection)
                throws Exception {
            if (connection instanceof Connection jdbc) {
                return new ReadOnlyState(jdbc, jdbc.isReadOnly());
            }
            return none();
        }

        private static ReadOnlyState none() {
            return new ReadOnlyState(null, false);
        }

        private void enable() throws Exception {
            if (connection == null || originalReadOnly) {
                return;
            }
            changed = true;
            connection.setReadOnly(true);
            if (!connection.isReadOnly()) {
                throw new IllegalStateException(
                        "JDBC 驱动未确认只读模式，企业只读查询已拒绝");
            }
        }

        private void restore() throws Exception {
            if (!changed) {
                return;
            }
            connection.setReadOnly(originalReadOnly);
            if (connection.isReadOnly() != originalReadOnly) {
                throw new IllegalStateException(
                        "JDBC 只读状态恢复校验失败");
            }
        }
    }

    static final class ConnectionNamespaceState {
        private final Connection connection;
        private final NamespaceAttribute attribute;
        private final String original;
        private final boolean changed;

        private ConnectionNamespaceState(
                Connection connection, NamespaceAttribute attribute,
                String original, boolean changed) {
            this.connection = connection;
            this.attribute = attribute;
            this.original = original;
            this.changed = changed;
        }

        static ConnectionNamespaceState none() {
            return new ConnectionNamespaceState(
                    null, null, null, false);
        }

        void restore() throws Exception {
            if (!changed) {
                return;
            }
            attribute.write(connection, original);
            String actual = attribute.read(connection);
            if (!sameNamespace(actual, original)) {
                throw new IllegalStateException(
                        "JDBC 默认数据库/Schema 恢复校验失败");
            }
        }
    }

    enum NamespaceAttribute {
        SCHEMA {
            @Override
            String read(Connection connection) throws Exception {
                return connection.getSchema();
            }

            @Override
            void write(Connection connection, String value)
                    throws Exception {
                connection.setSchema(value);
            }
        },
        CATALOG {
            @Override
            String read(Connection connection) throws Exception {
                return connection.getCatalog();
            }

            @Override
            void write(Connection connection, String value)
                    throws Exception {
                connection.setCatalog(value);
            }
        };

        abstract String read(Connection connection) throws Exception;

        abstract void write(Connection connection, String value)
                throws Exception;

        static NamespaceAttribute forDatabaseType(String dbType) {
            String normalized = dbType == null
                    ? "" : dbType.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "POSTGRESQL", "POSTGRES", "ORACLE", "H2" -> SCHEMA;
                case "MYSQL", "MARIADB", "MSSQL", "SQLSERVER",
                        "CLICKHOUSE", "MAXCOMPUTE" -> CATALOG;
                default -> throw new IllegalArgumentException(
                        "该数据库类型不支持默认数据库/Schema 切换: "
                                + normalized);
            };
        }
    }

    private static String requireConcreteNamespace(String value) {
        String target = value == null ? "" : value.trim();
        if (!CONCRETE_NAMESPACE_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException(
                    "默认数据库/Schema 必须是不带引号、通配符的具体标识符");
        }
        return target;
    }

    private static boolean sameNamespace(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return left.equalsIgnoreCase(right);
    }
}
