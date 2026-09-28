package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableEditRequest;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 表格增改删：加密审批载荷、行快照校验及目标库单事务。 */
@Service
public class EnterpriseTableEditService {
    private static final Set<String> EDITABLE_ENGINES = Set.of(
            "MYSQL", "POSTGRESQL", "ORACLE", "MSSQL", "SQLITE");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final int MAX_ROWS = 100;

    private final GrantService grants;
    private final DataSourceService sources;
    private final EnterpriseMetadataCatalogService metadata;
    private final MaskingService masking;
    private final ApprovalService approvals;
    private final CredentialService credentials;
    private final AuditService audit;
    private final SecurityUtil security;
    private final ObjectMapper json;

    public EnterpriseTableEditService(GrantService grants, DataSourceService sources,
                                      EnterpriseMetadataCatalogService metadata,
                                      MaskingService masking, ApprovalService approvals,
                                      CredentialService credentials, AuditService audit,
                                      SecurityUtil security, ObjectMapper json) {
        this.grants = grants;
        this.sources = sources;
        this.metadata = metadata;
        this.masking = masking;
        this.approvals = approvals;
        this.credentials = credentials;
        this.audit = audit;
        this.security = security;
        this.json = json;
    }

    public Map<String, Object> snapshot(String sourceName, String schema, String table)
            throws Exception {
        Context context = context(sourceName, schema, table);
        String reason = editabilityReason(context);
        List<String> lockedColumns = context.columns.stream()
                .filter(column -> column.isAutoIncrement()
                        || masked(context, column.getName()))
                .map(ColumnMetadata::getName).toList();
        if (reason != null) {
            return Map.of("editable", false, "reason", reason,
                    "columns", context.columns, "primaryKeys", context.primaryKeys,
                    "lockedColumns", lockedColumns, "rows", List.of());
        }
        audit.recordCurrent(context.grant.getWorkspaceId(), "TABLE_EDIT_SNAPSHOT",
                context.grant.getDataSourceId(), context.grant.getGrantedSourceName(), true, null);
        ConnectionService.ActiveConnection active = sources.resolveActiveConnection(
                context.grant.getDataSourceId());
        if (!(active.connection instanceof Connection jdbc)
                || !active.driver.getCapabilities().isSupportsTransaction()) {
            return Map.of("editable", false, "reason", "该连接不支持可靠事务",
                    "columns", context.columns, "primaryKeys", context.primaryKeys,
                    "lockedColumns", lockedColumns, "rows", List.of());
        }
        List<Map<String, Object>> raw;
        String sql = "SELECT * FROM " + quotedTable(context);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire();
             Statement statement = jdbc.createStatement()) {
            if (!jdbc.getMetaData().supportsTransactionIsolationLevel(
                    Connection.TRANSACTION_SERIALIZABLE)) {
                return Map.of("editable", false, "reason", "该连接不支持可验证冲突的事务隔离",
                        "columns", context.columns, "primaryKeys", context.primaryKeys,
                        "lockedColumns", lockedColumns, "rows", List.of());
            }
            statement.setMaxRows(MAX_ROWS + 1);
            try (ResultSet result = statement.executeQuery(sql)) {
                raw = readRows(result, MAX_ROWS + 1);
            }
        }
        boolean truncated = raw.size() > MAX_ROWS;
        if (truncated) raw = new ArrayList<>(raw.subList(0, MAX_ROWS));
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Map<String, Object> key = keyOf(context, row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("token", rowToken(context, row));
            item.put("values", new LinkedHashMap<>(row));
            visible.add(item);
        }
        QueryResult masked = new QueryResult();
        masked.setColumns(context.columns.stream().map(ColumnMetadata::getName).toList());
        masked.setRows(visible.stream()
                .map(item -> (Map<String, Object>) item.get("values")).toList());
        masking.applyMasking(masked, context.grant.getWorkspaceId(),
                context.grant.getDataSourceId(),
                SqlParseUtil.requireEnterpriseReadOnly(
                        "SELECT * FROM " + context.table.getQualifiedName()));
        return Map.of("editable", true, "reason", "", "columns", context.columns,
                "primaryKeys", context.primaryKeys, "lockedColumns", lockedColumns,
                "rows", visible,
                "truncated", truncated);
    }

    @Transactional
    public Map<String, Object> request(TableEditRequest request) throws Exception {
        if (request == null) throw new IllegalArgumentException("表格编辑请求不能为空");
        Context context = context(request.grantedSourceName(), request.schema(), request.table());
        requireEditable(context);
        validateChanges(context, request.changes(), true);
        String payload = json.writeValueAsString(Map.of(
                "schema", context.table.getSchema(), "table", context.table.getName(),
                "changes", request.changes()));
        ApprovalRequest approval = approvals.createTableEdit(context.grant, context.user,
                payload, request.reason());
        audit.recordCurrent(context.grant.getWorkspaceId(), "TABLE_EDIT_REQUEST",
                context.grant.getDataSourceId(), context.grant.getGrantedSourceName(), true, null);
        return approvals.toView(approval, true);
    }

    public Map<String, Object> execute(String approvalId) throws Exception {
        User user = security.requireCurrentUser();
        String workspace = security.requireCurrentWorkspace();
        ApprovalRequest approval = approvals.get(approvalId);
        if (!workspace.equals(approval.getWorkspaceId())
                || !user.getId().equals(approval.getApplicantId())
                || !"TABLE_EDIT".equals(approval.getOperationType())) {
            throw new IllegalArgumentException("审批单不存在或无权执行");
        }
        Grant grant = grants.resolveForUser(user.getId(), workspace,
                approval.getGrantedSourceName());
        if (!grant.getId().equals(approval.getGrantId())) {
            throw new IllegalArgumentException("原授权已变化，请重新申请");
        }
        String payload = approvals.claimTableEdit(approvalId, user, grant);
        TableEditRequest request;
        boolean externalCommitted = false;
        try {
            var node = json.readTree(payload);
            request = new TableEditRequest(grant.getGrantedSourceName(),
                    node.path("schema").asText(), node.path("table").asText(),
                    json.readerForListOf(TableEditRequest.Change.class)
                            .readValue(node.path("changes")), null);
        } catch (Exception exception) {
            approvals.markExecutionResult(approvalId, false, "审批载荷无效");
            throw new IllegalArgumentException("审批载荷无效", exception);
        }
        try {
            Context context = context(request.grantedSourceName(),
                    request.schema(), request.table());
            requireEditable(context);
            validateChanges(context, request.changes(), false);
            // 本地审计先落库；失败时不向目标数据库发送任何写入。
            audit.record(workspace, user.getId(), user.getUsername(), "ANALYST",
                    grant.getDataSourceId(), grant.getGrantedSourceName(),
                    context.source.getDbType(), "TABLE_EDIT_START", "TABLE_EDIT_START",
                    null, 0, 0, 0, true, null, approvalId);
            int count = apply(context, request.changes());
            externalCommitted = true;
            audit.record(workspace, user.getId(), user.getUsername(), "ANALYST",
                    grant.getDataSourceId(), grant.getGrantedSourceName(),
                    context.source.getDbType(), "TABLE_EDIT", "TABLE_EDIT_DONE",
                    null, count, 0, 0, true, null, approvalId);
            approvals.markExecutionResult(approvalId, true, "已提交 " + count + " 项表格变更");
            return Map.of("success", true, "count", count);
        } catch (Exception exception) {
            try {
                if (externalCommitted || exception instanceof UncertainCommitException) {
                    approvals.markExecutionUnknown(approvalId,
                            "目标数据库提交结果不明，禁止自动重试，请人工核验");
                } else if ("EXECUTING".equals(approvals.get(approvalId).getStatus())) {
                    approvals.markExecutionResult(approvalId, false, "表格编辑失败，已回滚或未发送写入");
                }
            } catch (Exception stateFailure) {
                throw new IllegalStateException("审批执行状态无法确认，请人工核验", stateFailure);
            }
            throw exception;
        }
    }

    private Context context(String sourceName, String schema, String table) throws Exception {
        User user = security.requireCurrentUser();
        String workspace = security.requireCurrentWorkspace();
        Grant grant = grants.resolveForUser(user.getId(), workspace, sourceName);
        DataSource source = sources.getEntity(grant.getDataSourceId());
        if (!workspace.equals(source.getWorkspaceId())) {
            throw new IllegalArgumentException("数据源不属于当前工作空间");
        }
        EnterpriseMetadataCatalog catalog = metadata.catalog(sourceName, false);
        EnterpriseMetadataCatalog.Table selected = catalog.getTables().stream()
                .filter(item -> item.getSchema().equalsIgnoreCase(schema)
                        && item.getName().equalsIgnoreCase(table))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "表不存在或不在当前授权范围内"));
        List<ColumnMetadata> columns = metadata.columns(sourceName,
                selected.getNamespace(), selected.getName());
        List<String> primaryKeys = columns.stream().filter(ColumnMetadata::isPrimaryKey)
                .map(ColumnMetadata::getName).toList();
        return new Context(user, grant, source, selected, columns, primaryKeys);
    }

    private String editabilityReason(Context context) {
        if (!"DML_ALLOWED".equals(context.grant.getSqlCapability())) return "当前授权只读";
        if (!EDITABLE_ENGINES.contains(context.source.getDbType().toUpperCase(Locale.ROOT))) {
            return "该引擎不支持表格事务编辑；可继续浏览和受控查询";
        }
        if (!"TABLE".equalsIgnoreCase(context.table.getType())
                && !"BASE TABLE".equalsIgnoreCase(context.table.getType())) {
            return "仅基础表支持直接编辑";
        }
        if (context.primaryKeys.isEmpty()) return "表缺少稳定主键";
        if (context.columns.size() > 100) return "表字段过多，请使用受控 SQL";
        for (String key : context.primaryKeys) {
            if (masked(context, key)) return "主键受脱敏规则保护，不可直接编辑";
        }
        return null;
    }

    private void requireEditable(Context context) {
        String reason = editabilityReason(context);
        if (reason != null) throw new IllegalArgumentException(reason);
    }

    private void validateChanges(Context context, List<TableEditRequest.Change> changes,
                                 boolean checkTokens) throws Exception {
        if (changes == null || changes.isEmpty() || changes.size() > MAX_ROWS) {
            throw new IllegalArgumentException("每批须包含 1-100 项变更");
        }
        Set<String> usedKeys = new LinkedHashSet<>();
        ConnectionService.ActiveConnection active = sources.resolveActiveConnection(
                context.grant.getDataSourceId());
        if (!(active.connection instanceof Connection jdbc)
                || !active.driver.getCapabilities().isSupportsTransaction()) {
            throw new IllegalArgumentException("该连接不支持可靠事务");
        }
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            for (TableEditRequest.Change change : changes) {
                if (change == null || change.action() == null
                        || !Set.of("INSERT", "UPDATE", "DELETE").contains(change.action())) {
                    throw new IllegalArgumentException("变更动作无效");
                }
                if ("INSERT".equals(change.action())) {
                    validateValues(context, change.values(), true);
                    continue;
                }
                requireKey(context, change.key());
                String key = json.writeValueAsString(change.key());
                if (!usedKeys.add(key)) throw new IllegalArgumentException("同一行不能重复暂存变更");
                if (change.token() == null || !change.token().matches("[A-Za-z0-9_-]{43}")) {
                    throw new IllegalArgumentException("行快照标识无效，请重新加载表格");
                }
                if ("UPDATE".equals(change.action())) validateValues(context, change.values(), false);
                if (checkTokens) {
                    Map<String, Object> current = readCurrent(jdbc, context, change.key());
                    if (current == null || !change.token().equals(rowToken(context, current))) {
                        throw new IllegalStateException("表数据已变化，请刷新后重新暂存");
                    }
                }
            }
        }
    }

    private void validateValues(Context context, Map<String, Object> values, boolean insert) {
        if (values == null || values.isEmpty() || values.size() > 100) {
            throw new IllegalArgumentException("新增或修改内容不能为空");
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            ColumnMetadata column = context.columns.stream()
                    .filter(item -> item.getName().equals(name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("字段不存在: " + name));
            if (column.isAutoIncrement() || (!insert && column.isPrimaryKey())
                    || masked(context, name)) {
                throw new IllegalArgumentException("字段不可直接编辑: " + name);
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof TemporalAccessor)) {
                throw new IllegalArgumentException("字段值类型不支持表格编辑: " + name);
            }
            bindValue(context, name, value);
        }
    }

    private void requireKey(Context context, Map<String, Object> key) {
        if (key == null || key.size() != context.primaryKeys.size()
                || !key.keySet().containsAll(context.primaryKeys)
                || key.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("行主键无效，请刷新表格");
        }
    }

    private int apply(Context context, List<TableEditRequest.Change> changes) throws Exception {
        ConnectionService.ActiveConnection active = sources.resolveActiveConnection(
                context.grant.getDataSourceId());
        if (!(active.connection instanceof Connection jdbc)) {
            throw new IllegalArgumentException("仅 JDBC 事务库支持表格编辑");
        }
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            if (!jdbc.getAutoCommit()) {
                throw new IllegalStateException("目标连接已有未完成事务，请稍后重试");
            }
            if (!jdbc.getMetaData().supportsTransactionIsolationLevel(
                    Connection.TRANSACTION_SERIALIZABLE)) {
                throw new IllegalStateException("目标数据库不支持可靠的串行化事务隔离");
            }
            int previousIsolation = jdbc.getTransactionIsolation();
            jdbc.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            boolean commitAttempted = false;
            try {
                jdbc.setAutoCommit(false);
                for (TableEditRequest.Change change : changes) {
                    if (!"INSERT".equals(change.action())) {
                        Map<String, Object> current = readCurrent(jdbc, context, change.key());
                        if (current == null || !change.token().equals(rowToken(context, current))) {
                            throw new IllegalStateException("表数据已变化，整批变更已取消");
                        }
                    }
                    if (executeChange(jdbc, context, change) != 1) {
                        throw new IllegalStateException("影响行数不等于 1，整批变更已取消");
                    }
                }
                commitAttempted = true;
                jdbc.commit();
                return changes.size();
            } catch (Exception exception) {
                boolean rollbackFailed = false;
                try { jdbc.rollback(); }
                catch (Exception rollback) { rollbackFailed = true; }
                if (commitAttempted || rollbackFailed) {
                    throw new UncertainCommitException("目标数据库提交状态不明", exception);
                }
                throw exception;
            } finally {
                try {
                    jdbc.setAutoCommit(true);
                    jdbc.setTransactionIsolation(previousIsolation);
                }
                catch (Exception restore) {
                    sources.disconnect(context.grant.getDataSourceId());
                    throw new UncertainCommitException("目标连接事务状态无法恢复", restore);
                }
            }
        }
    }

    private int executeChange(Connection jdbc, Context context,
                              TableEditRequest.Change change) throws Exception {
        String table = quotedTable(context);
        Map<String, Object> values = change.values();
        String sql;
        List<Object> parameters = new ArrayList<>();
        if ("INSERT".equals(change.action())) {
            sql = "INSERT INTO " + table + " (" + String.join(", ",
                    values.keySet().stream().map(name -> quote(context, name)).toList())
                    + ") VALUES (" + String.join(", ",
                    values.keySet().stream().map(name -> "?").toList()) + ")";
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                parameters.add(bindValue(context, entry.getKey(), entry.getValue()));
            }
        } else {
            String where = String.join(" AND ", context.primaryKeys.stream()
                    .map(key -> quote(context, key) + " = ?").toList());
            if ("UPDATE".equals(change.action())) {
                sql = "UPDATE " + table + " SET " + String.join(", ",
                        values.keySet().stream().map(name -> quote(context, name) + " = ?").toList())
                        + " WHERE " + where;
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    parameters.add(bindValue(context, entry.getKey(), entry.getValue()));
                }
            } else {
                sql = "DELETE FROM " + table + " WHERE " + where;
            }
            for (String key : context.primaryKeys) {
                parameters.add(bindValue(context, key, change.key().get(key)));
            }
        }
        try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            statement.setQueryTimeout(30);
            return statement.executeUpdate();
        }
    }

    private Map<String, Object> readCurrent(Connection jdbc, Context context,
                                            Map<String, Object> key) throws Exception {
        String where = String.join(" AND ", context.primaryKeys.stream()
                .map(name -> quote(context, name) + " = ?").toList());
        try (PreparedStatement statement = jdbc.prepareStatement(
                "SELECT * FROM " + quotedTable(context) + " WHERE " + where)) {
            for (int i = 0; i < context.primaryKeys.size(); i++) {
                String column = context.primaryKeys.get(i);
                statement.setObject(i + 1, bindValue(context, column, key.get(column)));
            }
            statement.setMaxRows(2);
            statement.setQueryTimeout(30);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = readRows(result, 2);
                if (rows.size() > 1) throw new IllegalStateException("主键定位多行，已阻止编辑");
                return rows.isEmpty() ? null : rows.get(0);
            }
        }
    }

    private List<Map<String, Object>> readRows(ResultSet result, int limit) throws Exception {
        ResultSetMetaData meta = result.getMetaData();
        if (meta.getColumnCount() > 100) throw new IllegalArgumentException("表字段过多");
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < limit && result.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnLabel(i);
                if (row.containsKey(column)) throw new IllegalArgumentException("表字段名重复");
                row.put(column, result.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 浏览器表单值按列类型转换后参数绑定，拒绝隐式类型转换造成的静默写入。 */
    private Object bindValue(Context context, String name, Object value) {
        if (value == null) return null;
        ColumnMetadata column = context.columns.stream()
                .filter(item -> item.getName().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("字段不存在: " + name));
        String type = column.getTypeName() == null ? ""
                : column.getTypeName().toUpperCase(Locale.ROOT);
        if (!(value instanceof String text)) return value;
        try {
            if (type.startsWith("BIGINT") || type.startsWith("INT8")) {
                return Long.valueOf(text.trim());
            }
            if (type.startsWith("INTEGER") || type.matches("INT(?:[24])?(?:\\(.*\\))?")
                    || type.startsWith("SMALLINT") || type.startsWith("TINYINT")) {
                return Integer.valueOf(text.trim());
            }
            if (type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                    || type.startsWith("NUMBER") || type.startsWith("MONEY")) {
                return new BigDecimal(text.trim());
            }
            if (type.startsWith("FLOAT") || type.startsWith("DOUBLE")
                    || type.startsWith("REAL")) {
                double number = Double.parseDouble(text.trim());
                if (!Double.isFinite(number)) throw new NumberFormatException("非有限数值");
                return number;
            }
            if (type.equals("BOOLEAN") || type.equals("BOOL") || type.equals("BIT")) {
                String normalized = text.trim().toLowerCase(Locale.ROOT);
                if (!Set.of("true", "false", "1", "0").contains(normalized)) {
                    throw new IllegalArgumentException("布尔值须为 true/false 或 1/0");
                }
                return "true".equals(normalized) || "1".equals(normalized);
            }
            if (type.equals("DATE")) return LocalDate.parse(text);
            if (type.startsWith("TIMESTAMP") || type.startsWith("DATETIME")) {
                return Timestamp.valueOf(text.replace('T', ' '));
            }
            if (type.startsWith("TIME")) return LocalTime.parse(text);
            return text;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("字段值与类型不匹配: " + name, exception);
        }
    }

    private Map<String, Object> keyOf(Context context, Map<String, Object> row) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (String name : context.primaryKeys) {
            Object value = row.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (value == null) throw new IllegalArgumentException("主键为空，不能直接编辑");
            key.put(name, value);
        }
        return key;
    }

    private String rowToken(Context context, Map<String, Object> row) throws Exception {
        return credentials.blindIndex("table-row-v1", context.grant.getWorkspaceId()
                + "\u0000" + context.grant.getId() + "\u0000"
                + context.table.getQualifiedName() + "\u0000"
                + json.writeValueAsString(row));
    }

    private boolean masked(Context context, String column) {
        return masking.isColumnMasked(context.grant.getWorkspaceId(),
                context.grant.getDataSourceId(), context.table.getQualifiedName(), column);
    }

    private String quotedTable(Context context) {
        String[] parts = context.table.getQualifiedName().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("表名必须包含明确的 Schema");
        }
        return String.join(".", java.util.Arrays.stream(parts)
                .map(part -> quote(context, part)).toList());
    }

    private String quote(Context context, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("表或字段标识符不支持直接编辑");
        }
        return switch (context.source.getDbType().toUpperCase(Locale.ROOT)) {
            case "MYSQL" -> "`" + value + "`";
            case "MSSQL" -> "[" + value + "]";
            default -> "\"" + value + "\"";
        };
    }

    private record Context(User user, Grant grant, DataSource source,
                           EnterpriseMetadataCatalog.Table table,
                           List<ColumnMetadata> columns, List<String> primaryKeys) { }

    private static final class UncertainCommitException extends Exception {
        private UncertainCommitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
