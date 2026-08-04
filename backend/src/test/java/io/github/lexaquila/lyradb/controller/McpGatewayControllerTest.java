package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.service.AiGatewayPrincipal;
import io.github.lexaquila.lyradb.service.AiGatewayRateLimiter;
import io.github.lexaquila.lyradb.service.AiKnowledgeService;
import io.github.lexaquila.lyradb.service.GovernedReadAgentService;
import io.github.lexaquila.lyradb.service.MaxComputeIntelligenceService;
import io.github.lexaquila.lyradb.service.McpOriginPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpGatewayControllerTest {

    @Mock private AiKnowledgeService knowledgeService;
    @Mock private GovernedReadAgentService readAgentService;
    @Mock private MaxComputeIntelligenceService maxComputeService;
    private McpGatewayController controller;
    private AiGatewayPrincipal principal;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        controller = new McpGatewayController(
                knowledgeService, readAgentService, maxComputeService,
                new AiGatewayRateLimiter(
                        properties, new io.github.lexaquila.lyradb.service.AiOperationalMetrics()),
                new McpOriginPolicy(properties), properties,
                new ObjectMapper());
        principal = new AiGatewayPrincipal(
                "token-1", "agent-user", "user-1",
                "workspace-1", "grant-1", "sales-source",
                Set.of(AgentGatewayScope.KNOWLEDGE_READ,
                        AgentGatewayScope.READ_PLAN,
                        AgentGatewayScope.READ_EXECUTE,
                        AgentGatewayScope.MAXCOMPUTE_ANALYZE));
    }

    @Test
    void discoverUsesModernProtocolWithoutSessionHandshake() {
        MockHttpServletRequest request = request(
                "server/discover", null);
        var response = controller.handle(
                rpc("1", "server/discover", Map.of()),
                principal, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(McpGatewayController.PROTOCOL_VERSION,
                response.getHeaders().getFirst("MCP-Protocol-Version"));
        String body = response.getBody().toString();
        assertTrue(body.contains("serverInfo"));
        assertTrue(body.contains("2026-07-28"));
        assertFalse(body.contains("sessionId"));
    }

    @Test
    void toolListNeverExposesExecuteOrWriteTool() {
        MockHttpServletRequest request = request("tools/list", null);
        var response = controller.handle(
                rpc("2", "tools/list", Map.of()), principal, request);

        String body = response.getBody().toString();
        assertTrue(body.contains("knowledge.search"));
        assertTrue(body.contains("sql.read.plan"));
        assertFalse(body.contains("sql.read.execute"));
        assertFalse(body.contains("write"));
    }

    @Test
    void mismatchedMethodHeaderFailsClosed() {
        MockHttpServletRequest request = request("tools/list", null);
        request.removeHeader("Mcp-Method");
        request.addHeader("Mcp-Method", "tools/call");

        var response = controller.handle(
                rpc("3", "tools/list", Map.of()), principal, request);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("-32600"));
    }

    @Test
    void planToolBindsTokenSourceAndReturnsHandleOnly() {
        AiReadAgentPlanView plan = new AiReadAgentPlanView(
                "run-1", "a".repeat(64), "sales-source",
                "SELECT * FROM sales.orders", null,
                Set.of("sales.orders"), 100, 0, "R2",
                Instant.now().plusSeconds(300),
                List.of("预检", "等待确认"), true);
        when(readAgentService.plan(eq("workspace-1"), any()))
                .thenReturn(plan);
        Map<String, Object> arguments = Map.of(
                "question", "查询订单",
                "sql", "SELECT * FROM sales.orders");
        MockHttpServletRequest request = request(
                "tools/call", "sql.read.plan");

        var response = controller.handle(
                rpc("4", "tools/call", Map.of(
                        "name", "sql.read.plan",
                        "arguments", arguments)), principal, request);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody().toString();
        assertTrue(body.contains("run-1"));
        assertTrue(body.contains("尚未执行"));
        org.mockito.ArgumentCaptor<io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest>
                captor = org.mockito.ArgumentCaptor.forClass(
                io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest.class);
        verify(readAgentService).plan(eq("workspace-1"), captor.capture());
        assertEquals("sales-source",
                captor.getValue().getGrantedSourceName());
    }

    @Test
    void crossOriginRequestIsRejected() {
        MockHttpServletRequest request = request("tools/list", null);
        request.addHeader("Origin", "https://evil.example");

        var response = controller.handle(
                rpc("5", "tools/list", Map.of()), principal, request);

        assertEquals(403, response.getStatusCode().value());
    }

    private static MockHttpServletRequest request(
            String method, String toolName) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/agent-gateway/mcp");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(80);
        request.addHeader("MCP-Protocol-Version",
                McpGatewayController.PROTOCOL_VERSION);
        request.addHeader("Mcp-Method", method);
        if (toolName != null) {
            request.addHeader("Mcp-Name", toolName);
        }
        return request;
    }

    private static Map<String, Object> rpc(
            Object id, String method, Map<String, Object> params) {
        Map<String, Object> withMeta = new java.util.LinkedHashMap<>(params);
        withMeta.put("_meta", Map.of("protocolVersion",
                McpGatewayController.PROTOCOL_VERSION));
        return Map.of("jsonrpc", "2.0", "id", id,
                "method", method, "params", withMeta);
    }
}
