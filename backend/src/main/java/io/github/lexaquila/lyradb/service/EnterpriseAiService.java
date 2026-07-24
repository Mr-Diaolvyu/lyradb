package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 企业 AI SQL 助手（NL→SQL）
 *
 * <p>
 * 流程：解析授权→取连接→装配 schema 上下文(仅授权可见表/列，不含数据值)→
 * 调用 OpenAI-compatible Provider→解析 SQL→护栏(只读直接执行/DML 需审批/DDL 拒绝)→
 * 只读经 {@link EnterpriseQueryService} 执行(复用授权校验+审计)。
 * </p>
 */
@Service
public class EnterpriseAiService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseAiService.class);

    private static final Pattern SQL_BLOCK = Pattern.compile("(?s)```sql\\s*(.*?)```");
    private static final int MAX_TABLES = 15;
    private static final int MAX_COLS = 20;

    private final AiProviderService aiProviderService;
    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final EnterpriseQueryService enterpriseQueryService;
    private final SecurityUtil securityUtil;

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
     * @param grantedSourceName 逻辑数据源
     * @param message           自然语言问题
     * @param history           历史对话 [{role,content}]
     * @return {sql, explanation, executed, needsApproval, result?, error?}
     */
    public Map<String, Object> chat(String grantedSourceName, String message, List<Map<String, String>> history) {
        Map<String, Object> out = new HashMap<>();
        User user = securityUtil.currentUser();
        if (user == null)
            throw new RuntimeException("未登录");

        Grant grant = grantService.resolveForUser(user.getId(), grantedSourceName);

        // 先检查 Provider 配置（廉价），再连库装配上下文
        AiProviderConfig config;
        try {
            config = aiProviderService.resolveDefault();
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
            return out;
        }

        ConnectionService.ActiveConnection ac = dataSourceService.resolveActiveConnection(grant.getDataSourceId());
        String dbType = ac.driver.getDriverInfo() != null ? ac.driver.getDriverInfo().getDbType() : "SQL";
        String[] schemaHolder = new String[1];
        String schemaContext = buildSchemaContext(ac, grant, schemaHolder);
        String schema = schemaHolder[0];

        // 装配消息
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是 SQL 生成助手。仅生成 " + dbType + " 方言的 SQL。"
                        + ("READ_ONLY".equalsIgnoreCase(grant.getSqlCapability()) ? "只允许 SELECT/EXPLAIN。"
                                : "允许 SELECT 与 DML。")
                        + "只能使用下面给出的表/列，不要虚构。返回格式：先一句中文说明，再用 ```sql 代码块给出 SQL。"));
        messages.add(Map.of("role", "system", "content", "可用的表与列（JSON）：\n" + schemaContext));
        if (history != null) {
            for (Map<String, String> h : history) {
                if (h.get("role") != null && h.get("content") != null)
                    messages.add(new HashMap<>(h));
            }
        }
        messages.add(Map.of("role", "user", "content", message));

        String reply;
        try {
            reply = aiProviderService.chat(config, messages);
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
            return out;
        }

        String sql = extractSql(reply);
        out.put("explanation", reply);
        out.put("sql", sql);

        if (sql == null || sql.isBlank()) {
            out.put("executed", false);
            return out;
        }

        String first = SqlParseUtil.firstWord(sql.toUpperCase());
        if (SqlParseUtil.DML_PREFIX.contains(first)) {
            // AI 生成的 DML 一律需审批，不自动执行
            out.put("executed", false);
            out.put("needsApproval", true);
            out.put("note", "AI 生成的 DML 需提交审批后由人工执行");
            return out;
        }
        if (!SqlParseUtil.READ_PREFIX.contains(first)) {
            out.put("executed", false);
            out.put("error", "AI 拒绝生成 DDL 或未知语句类型");
            return out;
        }

        // 只读：经企业查询执行（复用授权校验 + 审计）
        try {
            QueryResult result = enterpriseQueryService.executeQuery(grantedSourceName, sql, schema);
            out.put("executed", true);
            out.put("result", result);
            return out;
        } catch (Exception e) {
            out.put("executed", false);
            out.put("error", "执行失败: " + e.getMessage());
            return out;
        }
    }

    /**
     * 流式 AI 对话（SSE）：复用同步 {@link #chat} 逻辑，结果以事件流推送：
     * event:explanation / event:sql / event:result / event:needsApproval /
     * event:error，最后 complete。
     *
     * <p>
     * 真·逐 token 流式（{@code AiProviderService.streamChat}）已就绪，
     * 完整 schema-重建串联需真实 Provider KEY 验证，留待 v3.4。
     * </p>
     */
    public void chatStream(String grantedSourceName, String message, List<Map<String, String>> history,
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        try {
            Map<String, Object> res = chat(grantedSourceName, message, history);
            if (res.get("error") != null) {
                emitter.send(SseEmitter.event().name("error").data(res.get("error")));
            }
            if (res.get("explanation") != null) {
                emitter.send(SseEmitter.event().name("explanation").data(res.get("explanation")));
            }
            if (res.get("sql") != null) {
                emitter.send(SseEmitter.event().name("sql").data(res.get("sql")));
            }
            if (Boolean.TRUE.equals(res.get("needsApproval"))) {
                emitter.send(SseEmitter.event().name("needsApproval").data(res.get("sql")));
            }
            if (res.get("result") != null) {
                emitter.send(SseEmitter.event().name("result").data(res.get("result")));
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /** 装配 schema 上下文（仅授权可见表/列，不含数据值） */
    private String buildSchemaContext(ConnectionService.ActiveConnection ac, Grant grant, String[] schemaHolder) {
        try {
            List<TreeNode> roots = ac.driver.getTreeNodes(ac.connection, null);
            String schema = roots.stream()
                    .filter(n -> "DATABASE".equals(n.getType()) || "SCHEMA".equals(n.getType()))
                    .findFirst().map(TreeNode::getName).orElse(null);
            schemaHolder[0] = schema;

            List<TreeNode> tableNodes;
            try {
                tableNodes = ac.driver.getTreeNodes(ac.connection, schema);
            } catch (Exception e) {
                tableNodes = roots;
            }

            List<Map<String, Object>> tables = new ArrayList<>();
            Set<String> allowed = SqlParseUtil.splitCsv(grant.getAllowedTables());
            Set<String> blocked = SqlParseUtil.splitCsv(grant.getBlockedTables());
            int tableCount = 0;
            for (TreeNode n : tableNodes) {
                if (!"TABLE".equals(n.getType()) && !"VIEW".equals(n.getType()) && !"COLLECTION".equals(n.getType()))
                    continue;
                String tName = n.getName();
                // 白名单/黑名单过滤
                if (!allowed.isEmpty() && !SqlParseUtil.matchAny(tName, allowed))
                    continue;
                if (SqlParseUtil.matchAny(tName, blocked))
                    continue;
                if (tableCount++ >= MAX_TABLES)
                    break;
                List<String> cols = new ArrayList<>();
                try {
                    List<?> columns = ac.driver.getTableColumns(ac.connection, schema, tName);
                    for (Object c : columns) {
                        if (cols.size() >= MAX_COLS)
                            break;
                        if (c instanceof io.github.lexaquila.lyradb.model.dto.ColumnMetadata cm) {
                            cols.add(cm.getName() + (cm.getTypeName() != null ? ":" + cm.getTypeName() : ""));
                        }
                    }
                } catch (Exception ignored) {
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("table", tName);
                t.put("columns", cols);
                tables.add(t);
            }
            return objectMapper().writeValueAsString(Map.of("dbType",
                    ac.driver.getDriverInfo() != null ? ac.driver.getDriverInfo().getDbType() : "SQL", "tables",
                    tables));
        } catch (Exception e) {
            log.warn("装配 schema 上下文失败: {}", e.getMessage());
            return "[]";
        }
    }

    private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private String extractSql(String reply) {
        if (reply == null)
            return null;
        Matcher m = SQL_BLOCK.matcher(reply);
        if (m.find())
            return m.group(1).trim();
        return null;
    }
}
