package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.config.AgentGatewayAuthenticationFilter;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.GatewayKnowledgeSearchView;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightRequest;
import io.github.lexaquila.lyradb.service.AiGatewayPrincipal;
import io.github.lexaquila.lyradb.service.AiGatewayRateLimitException;
import io.github.lexaquila.lyradb.service.AiGatewayRateLimiter;
import io.github.lexaquila.lyradb.service.AiKnowledgeService;
import io.github.lexaquila.lyradb.service.GovernedReadAgentService;
import io.github.lexaquila.lyradb.service.MaxComputeIntelligenceService;
import io.github.lexaquila.lyradb.service.McpOriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP 2026-07-28 Streamable HTTP 单端点，无协议会话或任意工具直通。 */
@RestController
@RequestMapping("/agent-gateway")
public class McpGatewayController {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String HEADER_PROTOCOL = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private final AiKnowledgeService knowledgeService;
    private final GovernedReadAgentService readAgentService;
    private final MaxComputeIntelligenceService maxComputeService;
    private final AiGatewayRateLimiter rateLimiter;
    private final McpOriginPolicy originPolicy;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public McpGatewayController(
            AiKnowledgeService knowledgeService,
            GovernedReadAgentService readAgentService,
            MaxComputeIntelligenceService maxComputeService,
            AiGatewayRateLimiter rateLimiter,
            McpOriginPolicy originPolicy,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.readAgentService = readAgentService;
        this.maxComputeService = maxComputeService;
        this.rateLimiter = rateLimiter;
        this.originPolicy = originPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(
            @RequestBody Map<String, Object> body,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal,
            HttpServletRequest request) {
        Object id = body == null ? null : body.get("id");
        try {
            originPolicy.validate(request);
            validateBodySize(body, request);
            McpRequest parsed = parse(body, request);
            if (parsed.notification()) {
                rateLimiter.requireAllowed(
                        principal, "mcp.notification", false);
                return ResponseEntity.accepted()
                        .header(HEADER_PROTOCOL, PROTOCOL_VERSION)
                        .build();
            }
            Object result = switch (parsed.method()) {
                case "server/discover" -> discover(principal);
                case "tools/list" -> listTools(principal);
                case "tools/call" -> callTool(
                        principal, parsed.params());
                default -> throw new McpException(
                        -32601, "MCP 方法不存在", HttpStatus.BAD_REQUEST);
            };
            return jsonResponse(HttpStatus.OK,
                    rpcResult(parsed.id(), result));
        } catch (AiGatewayRateLimitException exception) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HEADER_PROTOCOL, PROTOCOL_VERSION)
                    .header(HttpHeaders.RETRY_AFTER,
                            String.valueOf(exception.getRetryAfterSeconds()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rpcError(id, -32029, exception.getMessage()));
        } catch (AccessDeniedException exception) {
            return jsonResponse(HttpStatus.FORBIDDEN,
                    rpcError(id, -32003, "MCP 请求无权访问"));
        } catch (McpException exception) {
            return jsonResponse(exception.status(),
                    rpcError(id, exception.code(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return jsonResponse(HttpStatus.BAD_REQUEST,
                    rpcError(id, -32602, exception.getMessage()));
        } catch (RuntimeException exception) {
            return jsonResponse(HttpStatus.OK,
                    rpcError(id, -32603, "MCP 工具调用失败"));
        }
    }

    private Object discover(AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(principal, "mcp.discover", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("serverInfo", Map.of(
                "name", "lyradb-trusted-intelligence-gateway",
                "version", properties.getVersion()));
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false)));
        result.put("authorization", Map.of(
                "workspaceId", principal.workspaceId(),
                "grantId", principal.grantId(),
                "grantedSourceName", principal.grantedSourceName(),
                "scopes", principal.scopes().stream()
                        .map(AgentGatewayScope::wireName).sorted().toList()));
        result.put("instructions",
                "所有工具由服务端确定性白名单提供；无写入工具；只读计划必须在独立人工确认通道执行。");
        return result;
    }

    private Object listTools(AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(principal, "mcp.tools.list", false);
        return Map.of("tools", toolDefinitions(principal));
    }

    private Object callTool(
            AiGatewayPrincipal principal, Map<String, Object> params) {
        requireOnly(params, Set.of("_meta", "name", "arguments"));
        String name = requiredString(params.get("name"), "工具名称", 100);
        Map<String, Object> arguments = objectMap(
                params.get("arguments"), "工具参数", true);
        boolean expensive = "sql.read.plan".equals(name)
                || "maxcompute.preflight".equals(name);
        rateLimiter.requireAllowed(
                principal, "mcp.tool." + name, expensive);

        Object structured = switch (name) {
            case "knowledge.search" -> searchKnowledge(
                    principal, arguments);
            case "sql.read.plan" -> plan(principal, arguments);
            case "maxcompute.preflight" -> maxComputePreflight(
                    principal, arguments);
            case "maxcompute.diagnose" -> maxComputeDiagnose(
                    principal, arguments);
            default -> throw new McpException(
                    -32602, "工具不在服务端允许列表",
                    HttpStatus.BAD_REQUEST);
        };
        String summary = switch (name) {
            case "knowledge.search" -> "已返回当前工作空间的已审核知识上下文。";
            case "sql.read.plan" -> "已生成只读计划句柄；尚未执行，必须走独立人工确认通道。";
            case "maxcompute.preflight" -> "已完成 MaxCompute 只读专项预检；尚未执行。";
            case "maxcompute.diagnose" -> "已完成确定性任务摘要诊断；未自动重试。";
            default -> "工具调用完成。";
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of(
                "type", "text", "text", summary)));
        result.put("structuredContent", structured);
        result.put("isError", false);
        return result;
    }

    private GatewayKnowledgeSearchView searchKnowledge(
            AiGatewayPrincipal principal, Map<String, Object> arguments) {
        principal.requireScope(AgentGatewayScope.KNOWLEDGE_READ);
        requireOnly(arguments, Set.of("question"));
        String question = requiredString(
                arguments.get("question"), "问题", 20_000);
        AiKnowledgeService.KnowledgeContext context =
                knowledgeService.retrieveVerified(
                        principal.workspaceId(),
                        principal.grantedSourceName(), question);
        return new GatewayKnowledgeSearchView(
                context.promptJson(), context.evidence(),
                context.omittedContext());
    }

    private Object plan(
            AiGatewayPrincipal principal, Map<String, Object> arguments) {
        principal.requireScope(AgentGatewayScope.READ_PLAN);
        requireOnly(arguments, Set.of(
                "question", "sql", "defaultDatabase", "requestedRows",
                "estimatedCostMicros", "maxComputePreflightSha256"));
        AiReadAgentPlanRequest request = new AiReadAgentPlanRequest();
        request.setGrantedSourceName(principal.grantedSourceName());
        request.setQuestion(requiredString(
                arguments.get("question"), "问题", 20_000));
        request.setSql(requiredString(arguments.get("sql"), "SQL", 50_000));
        request.setDefaultDatabase(optionalString(
                arguments.get("defaultDatabase"), 200));
        request.setRequestedRows(optionalInteger(
                arguments.get("requestedRows"), "requestedRows"));
        request.setEstimatedCostMicros(optionalLong(
                arguments.get("estimatedCostMicros"),
                "estimatedCostMicros"));
        request.setMaxComputePreflightSha256(optionalString(
                arguments.get("maxComputePreflightSha256"), 64));
        return readAgentService.plan(principal.workspaceId(), request);
    }

    private Object maxComputePreflight(
            AiGatewayPrincipal principal, Map<String, Object> arguments) {
        principal.requireScope(AgentGatewayScope.MAXCOMPUTE_ANALYZE);
        requireOnly(arguments, Set.of(
                "sql", "defaultDatabase", "requiredPartitionColumns",
                "estimatedInputBytes", "estimatedCostMicros"));
        MaxComputePreflightRequest request = new MaxComputePreflightRequest();
        request.setGrantedSourceName(principal.grantedSourceName());
        request.setSql(requiredString(arguments.get("sql"), "SQL", 50_000));
        request.setDefaultDatabase(optionalString(
                arguments.get("defaultDatabase"), 200));
        request.setRequiredPartitionColumns(partitionMap(
                arguments.get("requiredPartitionColumns")));
        request.setEstimatedInputBytes(optionalLong(
                arguments.get("estimatedInputBytes"),
                "estimatedInputBytes"));
        request.setEstimatedCostMicros(optionalLong(
                arguments.get("estimatedCostMicros"),
                "estimatedCostMicros"));
        return maxComputeService.preflight(
                principal.workspaceId(), request);
    }

    private Object maxComputeDiagnose(
            AiGatewayPrincipal principal, Map<String, Object> arguments) {
        principal.requireScope(AgentGatewayScope.MAXCOMPUTE_ANALYZE);
        requireOnly(arguments, Set.of(
                "taskStatus", "errorCode", "errorMessage"));
        MaxComputeDiagnosticRequest request =
                new MaxComputeDiagnosticRequest();
        request.setTaskStatus(optionalString(
                arguments.get("taskStatus"), 32));
        request.setErrorCode(optionalString(
                arguments.get("errorCode"), 100));
        request.setErrorMessage(optionalString(
                arguments.get("errorMessage"), 2_000));
        return maxComputeService.diagnose(
                principal.workspaceId(), request);
    }

    private McpRequest parse(
            Map<String, Object> body, HttpServletRequest request) {
        if (body == null || !"2.0".equals(body.get("jsonrpc"))) {
            throw new McpException(
                    -32600, "JSON-RPC 请求格式无效", HttpStatus.BAD_REQUEST);
        }
        requireOnly(body, Set.of("jsonrpc", "id", "method", "params"));
        String method = requiredString(body.get("method"), "MCP 方法", 100);
        Map<String, Object> params = objectMap(
                body.get("params"), "MCP params", true);
        Map<String, Object> meta = objectMap(
                params.get("_meta"), "MCP _meta", false);
        if (!PROTOCOL_VERSION.equals(meta.get("protocolVersion"))) {
            throw new McpException(-32600,
                    "_meta.protocolVersion 必须为 " + PROTOCOL_VERSION,
                    HttpStatus.BAD_REQUEST);
        }
        if (!PROTOCOL_VERSION.equals(request.getHeader(HEADER_PROTOCOL))) {
            throw new McpException(-32600,
                    HEADER_PROTOCOL + " 不匹配", HttpStatus.BAD_REQUEST);
        }
        if (!method.equals(request.getHeader(HEADER_METHOD))) {
            throw new McpException(-32600,
                    HEADER_METHOD + " 与 JSON-RPC method 不匹配",
                    HttpStatus.BAD_REQUEST);
        }
        if ("tools/call".equals(method)) {
            String name = requiredString(
                    params.get("name"), "工具名称", 100);
            if (!name.equals(request.getHeader(HEADER_NAME))) {
                throw new McpException(-32600,
                        HEADER_NAME + " 与工具名称不匹配",
                        HttpStatus.BAD_REQUEST);
            }
        } else if (request.getHeader(HEADER_NAME) != null
                && !request.getHeader(HEADER_NAME).isBlank()) {
            throw new McpException(-32600,
                    "非工具调用不得发送 " + HEADER_NAME,
                    HttpStatus.BAD_REQUEST);
        }
        Object id = body.get("id");
        boolean notification = id == null
                && method.startsWith("notifications/");
        if (!notification && !(id instanceof String)
                && !(id instanceof Number)) {
            throw new McpException(-32600,
                    "MCP 请求 ID 必须是字符串或数字", HttpStatus.BAD_REQUEST);
        }
        return new McpRequest(id, method, params, notification);
    }

    private void validateBodySize(
            Map<String, Object> body, HttpServletRequest request) {
        int maximum = properties.getAi().getMcpMaxRequestBytes();
        long declared = request.getContentLengthLong();
        if (declared > maximum) {
            throw new McpException(-32600,
                    "MCP 请求体超过安全上限", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        try {
            if (objectMapper.writeValueAsBytes(body).length > maximum) {
                throw new McpException(-32600,
                        "MCP 请求体超过安全上限",
                        HttpStatus.PAYLOAD_TOO_LARGE);
            }
        } catch (McpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new McpException(-32600,
                    "MCP 请求体无法验证", HttpStatus.BAD_REQUEST);
        }
    }

    static List<Map<String, Object>> toolDefinitions(
            AiGatewayPrincipal principal) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (principal.scopes().contains(AgentGatewayScope.KNOWLEDGE_READ)) {
            tools.add(tool("knowledge.search", "检索当前工作空间已审核知识",
                    objectSchema(Map.of("question", stringSchema(
                            "检索问题", 20_000)), List.of("question")), true));
        }
        if (principal.scopes().contains(AgentGatewayScope.READ_PLAN)) {
            tools.add(tool("sql.read.plan",
                    "生成只读计划句柄；不会执行；必须在独立人工确认通道执行",
                    objectSchema(Map.of(
                            "question", stringSchema("原始业务问题", 20_000),
                            "sql", stringSchema("单条只读 SQL", 50_000),
                            "defaultDatabase", stringSchema("可选默认命名空间", 200),
                            "requestedRows", Map.of("type", "integer", "minimum", 1),
                            "estimatedCostMicros", Map.of("type", "integer", "minimum", 0),
                            "maxComputePreflightSha256", stringSchema("可选专项预检摘要", 64)),
                            List.of("question", "sql")), false));
        }
        if (principal.scopes().contains(
                AgentGatewayScope.MAXCOMPUTE_ANALYZE)) {
            tools.add(tool("maxcompute.preflight",
                    "只读分区、EXPLAIN 与成本预检；不会执行查询",
                    objectSchema(Map.of(
                            "sql", stringSchema("单条只读 MaxCompute SQL", 50_000),
                            "defaultDatabase", stringSchema("可选 Project", 200),
                            "requiredPartitionColumns", Map.of("type", "object"),
                            "estimatedInputBytes", Map.of("type", "integer", "minimum", 0),
                            "estimatedCostMicros", Map.of("type", "integer", "minimum", 0)),
                            List.of("sql")), false));
            tools.add(tool("maxcompute.diagnose",
                    "根据脱敏任务摘要做确定性诊断；不会自动重试",
                    objectSchema(Map.of(
                            "taskStatus", stringSchema("任务状态", 32),
                            "errorCode", stringSchema("脱敏错误码", 100),
                            "errorMessage", stringSchema("脱敏错误摘要", 2_000)),
                            List.of()), true));
        }
        return List.copyOf(tools);
    }

    private static Map<String, Object> tool(
            String name, String description,
            Map<String, Object> inputSchema, boolean readOnlyHint) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("title", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        tool.put("outputSchema", Map.of("type", "object"));
        tool.put("annotations", Map.of(
                "readOnlyHint", readOnlyHint,
                "destructiveHint", false,
                "idempotentHint", readOnlyHint,
                "openWorldHint", false));
        return tool;
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringSchema(
            String description, int maxLength) {
        return Map.of("type", "string", "description", description,
                "maxLength", maxLength);
    }

    private static Map<String, List<String>> partitionMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> values) || values.size() > 200) {
            throw new IllegalArgumentException("分区列声明格式无效");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || key.isBlank() || key.length() > 500
                    || !(entry.getValue() instanceof List<?> columns)
                    || columns.size() > 64) {
                throw new IllegalArgumentException("分区列声明格式无效");
            }
            List<String> parsed = new ArrayList<>();
            for (Object column : columns) {
                parsed.add(requiredString(column, "分区列", 200));
            }
            result.put(key.trim(), List.copyOf(parsed));
        }
        return Map.copyOf(result);
    }

    private static void requireOnly(
            Map<String, Object> values, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "包含未允许字段: " + String.join(",", unknown));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(
            Object raw, String field, boolean optional) {
        if (raw == null && optional) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(field + "必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + "字段名必须是字符串");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String requiredString(
            Object raw, String field, int maximum) {
        if (!(raw instanceof String value)
                || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maximum);
        }
        return value.trim();
    }

    private static String optionalString(Object raw, int maximum) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.length() > maximum) {
            throw new IllegalArgumentException("可选字符串格式无效");
        }
        return value.isBlank() ? null : value.trim();
    }

    private static Integer optionalInteger(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
        long value = number.longValue();
        if (value < 1 || value > Integer.MAX_VALUE
                || number.doubleValue() != value) {
            throw new IllegalArgumentException(field + "必须是正整数");
        }
        return (int) value;
    }

    private static Long optionalLong(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
        long value = number.longValue();
        if (value < 0 || number.doubleValue() != value) {
            throw new IllegalArgumentException(field + "必须是非负整数");
        }
        return value;
    }

    private ResponseEntity<Map<String, Object>> jsonResponse(
            HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status)
                .header(HEADER_PROTOCOL, PROTOCOL_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> rpcError(
            Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private record McpRequest(
            Object id, String method,
            Map<String, Object> params, boolean notification) {
    }

    private static final class McpException extends RuntimeException {
        private final int code;
        private final HttpStatus status;

        private McpException(int code, String message, HttpStatus status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        private int code() {
            return code;
        }

        private HttpStatus status() {
            return status;
        }
    }
}
