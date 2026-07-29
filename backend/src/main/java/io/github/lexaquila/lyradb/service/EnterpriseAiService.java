
package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业 AI SQL 助手（NL→SQL）。
 *
 * <p>服务仅向模型暴露当前工作空间、当前授权明确允许的表结构，不发送任何数据值。
 * 模型生成的 SQL 必须经过 AST 解析和资源授权校验；只读 SQL 复用企业查询治理链执行，
 * DML 只返回待审批状态，DDL、未知语句及解析失败均拒绝。</p>
 */
@Service
public class EnterpriseAiService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseAiService.class);
    private static final String CODE_FENCE = String.valueOf((char) 96).repeat(3);
    private static final Pattern SQL_BLOCK = Pattern.compile(
            "(?is)" + Pattern.quote(CODE_FENCE) + "\\s*sql\\s*(.*?)"
                    + Pattern.quote(CODE_FENCE));
    private static final Set<String> TABLE_TYPES =
            Set.of("TABLE", "VIEW", "COLLECTION");
    private static final Set<String> CONTAINER_TYPES =
            Set.of("DATABASE", "SCHEMA", "PROJECT");
    private static final int MAX_TABLES = 15;
    private static final int MAX_COLS = 20;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private final AiProviderService aiProviderService;
    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final EnterpriseQueryService enterpriseQueryService;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EnterpriseAiService(AiProviderService aiProviderService, GrantService grantService,
            DataSourceService dataSourceService, EnterpriseQueryService enterpriseQueryService,
            SecurityUtil securityUtil) {
        this.aiProviderService = aiProviderService;
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.enterpriseQueryService = enterpriseQueryService;
        this.securityUtil = securityUtil;
    }

    /**
     * @return sql、explanation、executed、needsApproval、result 或稳定错误信息
     */
    public Map<String, Object> chat(String workspaceId, String grantedSourceName, String message,
            List<Map<String, String>> history) {
        requireText(workspaceId, "workspaceId", 36);
        requireText(grantedSourceName, "grantedSourceName", 100);
        requireText(message, "message", MAX_MESSAGE_CHARS);

        User user = securityUtil.requireCurrentUser();
        Grant grant = grantService.resolveForUser(
                user.getId(), workspaceId, grantedSourceName.trim());
        if (!workspaceId.equals(grant.getWorkspaceId())) {
            throw new AccessDeniedException("逻辑数据源不属于当前工作空间");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        final AiProviderConfig config;
        try {
            config = aiProviderService.resolveDefault(workspaceId);
        } catch (RuntimeException exception) {
            log.warn("AI Provider 解析失败: workspace={}, type={}",
                    workspaceId, exception.getClass().getSimpleName());
            out.put("executed", false);
            out.put("error", "当前工作空间的 AI 服务不可用");
            return out;
        }

        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(grant.getDataSourceId());
        final SchemaContext schemaContext;
        try {
            schemaContext = buildSchemaContext(active, grant);
        } catch (RuntimeException exception) {
            log.error("AI 授权元数据装配失败: workspace={}, source={}, type={}",
                    workspaceId, grant.getDataSourceId(),
                    exception.getClass().getSimpleName(), exception);
            out.put("executed", false);
            out.put("error", "无法读取已授权的数据结构");
            return out;
        }

        String dbType = active.driver.getDriverInfo() != null
                ? active.driver.getDriverInfo().getDbType() : "SQL";
        List<Map<String, String>> messages = buildMessages(
                dbType, grant, schemaContext.json(), message, history);

        final String reply;
        try {
            reply = aiProviderService.chat(config, messages);
        } catch (RuntimeException exception) {
            log.warn("企业 AI 调用失败: workspace={}, provider={}, type={}",
                    workspaceId, config.getId(), exception.getClass().getSimpleName());
            out.put("executed", false);
            out.put("error", "AI 服务调用失败，请稍后重试");
            return out;
        }

        String sql = extractSql(reply);
        out.put("explanation", reply);
        out.put("sql", sql);
        out.put("executed", false);
        if (sql == null || sql.isBlank()) {
            return out;
        }

        final SqlParseUtil.Analysis analysis;
        try {
            analysis = SqlParseUtil.analyzeEnterprise(sql);
            authorizeGeneratedResources(
                    grant, analysis, schemaContext.defaultSchema());
        } catch (RuntimeException exception) {
            log.info("AI SQL 已被安全护栏拒绝: workspace={}, type={}",
                    workspaceId, exception.getClass().getSimpleName());
            out.put("error", "AI 生成的 SQL 未通过安全校验");
            return out;
        }

        if (analysis.type() == SqlParseUtil.StatementType.DML) {
            if (!"DML_ALLOWED".equalsIgnoreCase(grant.getSqlCapability())) {
                out.put("error", "当前授权仅允许只读 SQL");
                return out;
            }
            out.put("needsApproval", true);
            out.put("note", "AI 生成的 DML 需提交审批后由人工执行");
            return out;
        }

        try {
            QueryResult result = enterpriseQueryService.executeQuery(
                    grantedSourceName.trim(), sql, schemaContext.defaultSchema());
            out.put("executed", true);
            out.put("result", result);
            return out;
        } catch (Exception exception) {
            log.warn("AI 只读 SQL 执行失败: workspace={}, type={}",
                    workspaceId, exception.getClass().getSimpleName());
            out.put("error", "AI 生成的 SQL 执行失败");
            return out;
        }
    }

    /**
     * SSE 包装：实际治理逻辑与同步接口完全一致。
     */
    public void chatStream(String workspaceId, String grantedSourceName, String message,
            List<Map<String, String>> history, SseEmitter emitter) {
        try {
            Map<String, Object> result =
                    chat(workspaceId, grantedSourceName, message, history);
            sendIfPresent(emitter, "error", result.get("error"));
            sendIfPresent(emitter, "explanation", result.get("explanation"));
            sendIfPresent(emitter, "sql", result.get("sql"));
            if (Boolean.TRUE.equals(result.get("needsApproval"))) {
                emitter.send(SseEmitter.event()
                        .name("needsApproval").data(result.get("sql")));
            }
            sendIfPresent(emitter, "result", result.get("result"));
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    /**
     * 元数据读取的整个生命周期都持有物理连接租约。空表白名单表示零张表，
     * 不能退化为全部表；Schema 白名单存在时只遍历其明确允许的容器。
     */
    private SchemaContext buildSchemaContext(
            ConnectionService.ActiveConnection active, Grant grant) {
        Set<String> allowedTables = SqlParseUtil.splitCsv(grant.getAllowedTables());
        String dbType = active.driver.getDriverInfo() != null
                ? active.driver.getDriverInfo().getDbType() : "SQL";
        if (allowedTables.isEmpty()) {
            return new SchemaContext(toSchemaJson(dbType, List.of()), null);
        }

        Set<String> allowedSchemas = SqlParseUtil.splitCsv(grant.getAllowedSchemas());
        Set<String> blockedTables = SqlParseUtil.splitCsv(grant.getBlockedTables());
        if (allowedSchemas.isEmpty()) {
            throw new AccessDeniedException(
                    "AI 元数据授权必须显式配置 Schema 白名单");
        }
        if (allowedTables.stream().anyMatch(table -> !table.contains("."))) {
            throw new AccessDeniedException(
                    "AI 元数据表白名单必须使用 Schema.Table 完整限定名");
        }
        List<Map<String, Object>> tables = new ArrayList<>();

        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            List<TreeNode> roots = active.driver.getTreeNodes(active.connection, null);
            if (roots == null) {
                roots = List.of();
            }

            for (TreeNode root : roots) {
                if (tables.size() >= MAX_TABLES) {
                    break;
                }
                if (isTable(root)) {
                    // 有 Schema 层级的元数据树中，根级裸表无法唯一映射到授权资源。
                    continue;
                }
                if (!isContainer(root)) {
                    continue;
                }

                String schema = root.getName();
                if (!SqlParseUtil.matchAny(schema, allowedSchemas)) {
                    continue;
                }

                String parentPath = root.getPath() == null
                        || root.getPath().isBlank() ? schema : root.getPath();
                List<TreeNode> children =
                        active.driver.getTreeNodes(active.connection, parentPath);
                if (children == null) {
                    continue;
                }
                for (TreeNode child : children) {
                    if (tables.size() >= MAX_TABLES) {
                        break;
                    }
                    if (isTable(child)) {
                        addAuthorizedTable(active, schema, child, allowedTables,
                                blockedTables, tables);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取授权元数据已中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取授权元数据失败", exception);
        }
        return new SchemaContext(toSchemaJson(dbType, tables), null);
    }

    private void addAuthorizedTable(ConnectionService.ActiveConnection active, String schema,
            TreeNode node, Set<String> allowedTables, Set<String> blockedTables,
            List<Map<String, Object>> output) {
        String table = node.getName();
        String qualified = schema == null || schema.isBlank()
                ? table : schema + "." + table;
        boolean qualifiedAllowed = SqlParseUtil.matchAny(qualified, allowedTables);
        if (!qualifiedAllowed
                || matchesTable(table, qualified, blockedTables)) {
            return;
        }

        final List<ColumnMetadata> metadata;
        try {
            metadata = active.driver.getTableColumns(
                    active.connection, schema, table);
        } catch (Exception exception) {
            log.debug("忽略无法读取列元数据的授权表: {}", qualified);
            return;
        }
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        List<String> columns = new ArrayList<>();
        for (ColumnMetadata column : metadata) {
            if (columns.size() >= MAX_COLS) {
                break;
            }
            if (column != null && column.getName() != null
                    && !column.getName().isBlank()) {
                columns.add(column.getName()
                        + (column.getTypeName() == null
                                || column.getTypeName().isBlank()
                                ? "" : ":" + column.getTypeName()));
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("table", qualified);
        item.put("columns", columns);
        output.add(item);
    }

    private List<Map<String, String>> buildMessages(String dbType, Grant grant,
            String schemaJson, String message, List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是 SQL 生成助手。仅生成 " + dbType + " 方言的 SQL。"
                        + ("READ_ONLY".equalsIgnoreCase(grant.getSqlCapability())
                                ? "只允许只读查询。" : "允许只读查询与 DML，但 DML 不会自动执行。")
                        + "只能使用下面给出的表和列，不要虚构。"
                        + "只返回一句中文说明和一个三反引号 sql 代码块。"));
        messages.add(Map.of("role", "system", "content",
                "当前授权可用的表与列（JSON，不含数据值）：\n" + schemaJson));

        if (history != null) {
            int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            for (int index = start; index < history.size(); index++) {
                Map<String, String> item = history.get(index);
                if (item == null) {
                    continue;
                }
                String role = item.get("role");
                String content = item.get("content");
                if (!Set.of("user", "assistant").contains(role)
                        || content == null || content.isBlank()) {
                    continue;
                }
                messages.add(Map.of("role", role,
                        "content", bounded(content, MAX_MESSAGE_CHARS)));
            }
        }
        messages.add(Map.of("role", "user", "content", message.trim()));
        return messages;
    }

    /**
     * 与企业查询链使用相同的严格限定名语义，避免 AI 在审批前绕过授权范围。
     */
    private void authorizeGeneratedResources(Grant grant,
            SqlParseUtil.Analysis analysis, String defaultSchema) {
        Set<String> allowedTables = SqlParseUtil.splitCsv(grant.getAllowedTables());
        Set<String> blockedTables = SqlParseUtil.splitCsv(grant.getBlockedTables());
        Set<String> allowedSchemas = SqlParseUtil.splitCsv(grant.getAllowedSchemas());

        if (!analysis.tables().isEmpty() && allowedTables.isEmpty()) {
            throw new AccessDeniedException("授权未配置表白名单");
        }
        for (String table : analysis.tables()) {
            if (SqlParseUtil.matchAny(table, blockedTables)
                    || !SqlParseUtil.matchAny(table, allowedTables)) {
                throw new AccessDeniedException("SQL 引用了未授权表");
            }
            String schema = SqlParseUtil.schemaOf(table);
            if (schema == null || allowedSchemas.isEmpty()
                    || !SqlParseUtil.matchAny(schema, allowedSchemas)) {
                throw new AccessDeniedException("AI SQL 必须完整限定已授权 Schema.Table");
            }
        }
        if (defaultSchema != null && !defaultSchema.isBlank()
                && (allowedSchemas.isEmpty()
                || !SqlParseUtil.matchAny(defaultSchema, allowedSchemas))) {
            throw new AccessDeniedException("默认 Schema 未获授权");
        }
    }

    private String toSchemaJson(String dbType, List<Map<String, Object>> tables) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("dbType", dbType, "tables", tables));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化授权元数据失败", exception);
        }
    }

    private static boolean matchesTable(
            String table, String qualified, Set<String> patterns) {
        return SqlParseUtil.matchAny(table, patterns)
                || SqlParseUtil.matchAny(qualified, patterns);
    }

    private static boolean isTable(TreeNode node) {
        return node != null && TABLE_TYPES.contains(node.getType());
    }

    private static boolean isContainer(TreeNode node) {
        return node != null && CONTAINER_TYPES.contains(node.getType());
    }

    private static void sendIfPresent(
            SseEmitter emitter, String event, Object value) throws Exception {
        if (value != null) {
            emitter.send(SseEmitter.event().name(event).data(value));
        }
    }

    private static String extractSql(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher matcher = SQL_BLOCK.matcher(reply);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 必填且长度不得超过 " + maxLength);
        }
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength
                ? value : value.substring(value.length() - maxLength);
    }

    private record SchemaContext(String json, String defaultSchema) {
    }
}
