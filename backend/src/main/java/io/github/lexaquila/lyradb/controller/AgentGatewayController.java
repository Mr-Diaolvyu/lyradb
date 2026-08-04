package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.config.AgentGatewayAuthenticationFilter;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentCancelView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentConfirmRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentExecutionView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.model.dto.GatewayKnowledgeSearchRequest;
import io.github.lexaquila.lyradb.model.dto.GatewayKnowledgeSearchView;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticView;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightView;
import io.github.lexaquila.lyradb.service.AiGatewayPrincipal;
import io.github.lexaquila.lyradb.service.AiGatewayRateLimiter;
import io.github.lexaquila.lyradb.service.AiKnowledgeService;
import io.github.lexaquila.lyradb.service.GovernedReadAgentService;
import io.github.lexaquila.lyradb.service.MaxComputeIntelligenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 Bearer 身份的受控工具网关。
 *
 * <p>typed REST 保留独立人工确认执行通道；MCP 2026-07-28 端点只暴露
 * 服务端确定性只读分析与计划工具。不接受任意工具名、shell 或 SQL 直通。</p>
 */
@RestController
@RequestMapping("/agent-gateway/v1")
public class AgentGatewayController {

    private final AiKnowledgeService knowledgeService;
    private final GovernedReadAgentService readAgentService;
    private final MaxComputeIntelligenceService maxComputeService;
    private final AiGatewayRateLimiter rateLimiter;

    public AgentGatewayController(
            AiKnowledgeService knowledgeService,
            GovernedReadAgentService readAgentService,
            MaxComputeIntelligenceService maxComputeService,
            AiGatewayRateLimiter rateLimiter) {
        this.knowledgeService = knowledgeService;
        this.readAgentService = readAgentService;
        this.maxComputeService = maxComputeService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.capabilities", false);
        List<Map<String, String>> tools = new ArrayList<>();
        addTool(principal, tools, AgentGatewayScope.KNOWLEDGE_READ,
                "knowledge.search", "检索当前工作空间已审核知识");
        addTool(principal, tools, AgentGatewayScope.READ_PLAN,
                "sql.read.plan", "生成受治理只读计划");
        addTool(principal, tools, AgentGatewayScope.READ_EXECUTE,
                "sql.read.execute", "确认摘要后执行只读计划");
        addTool(principal, tools, AgentGatewayScope.MAXCOMPUTE_ANALYZE,
                "maxcompute.preflight", "执行分区与成本专项预检");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "typed-rest-v1");
        result.put("protocols", List.of(
                "typed-rest-v1", "mcp-2026-07-28"));
        result.put("mcpStatus", "available");
        result.put("mcpProtocolVersion", "2026-07-28");
        result.put("mcpEndpoint", "/agent-gateway/mcp");
        result.put("mcpExecutionToolsAllowed", false);
        result.put("humanConfirmationChannel", "typed-rest-v1");
        result.put("workspaceId", principal.workspaceId());
        result.put("grantId", principal.grantId());
        result.put("grantedSourceName", principal.grantedSourceName());
        result.put("tools", tools);
        result.put("arbitraryToolCallsAllowed", false);
        result.put("writeToolsAllowed", false);
        return result;
    }

    @PostMapping("/tools/knowledge/search")
    public GatewayKnowledgeSearchView searchKnowledge(
            @RequestBody GatewayKnowledgeSearchRequest request,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.knowledge.search", false);
        principal.requireScope(AgentGatewayScope.KNOWLEDGE_READ);
        if (request == null || request.getQuestion() == null
                || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("知识检索问题不能为空");
        }
        AiKnowledgeService.KnowledgeContext context =
                knowledgeService.retrieveVerified(
                        principal.workspaceId(),
                        principal.grantedSourceName(),
                        request.getQuestion());
        return new GatewayKnowledgeSearchView(
                context.promptJson(), context.evidence(),
                context.omittedContext());
    }

    @PostMapping("/tools/read/plans")
    public AiReadAgentPlanView plan(
            @RequestBody AiReadAgentPlanRequest request,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.read.plan", true);
        principal.requireScope(AgentGatewayScope.READ_PLAN);
        requireBoundSource(request == null ? null
                : request.getGrantedSourceName(), principal);
        return readAgentService.plan(principal.workspaceId(), request);
    }

    @PostMapping("/tools/read/plans/{runId}/execute")
    public AiReadAgentExecutionView execute(
            @PathVariable String runId,
            @RequestBody AiReadAgentConfirmRequest request,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.read.cancel", false);
        principal.requireScope(AgentGatewayScope.READ_EXECUTE);
        if (request == null) {
            throw new IllegalArgumentException("确认请求不能为空");
        }
        return readAgentService.execute(
                principal.workspaceId(), runId,
                request.getPlanSha256());
    }

    @PostMapping("/tools/read/plans/{runId}/cancel")
    public AiReadAgentCancelView cancel(
            @PathVariable String runId,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.read.execute", true);
        principal.requireScope(AgentGatewayScope.READ_EXECUTE);
        return readAgentService.cancel(
                principal.workspaceId(), runId);
    }

    @PostMapping("/tools/maxcompute/preflight")
    public MaxComputePreflightView maxComputePreflight(
            @RequestBody MaxComputePreflightRequest request,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.maxcompute.diagnose", false);
        principal.requireScope(AgentGatewayScope.MAXCOMPUTE_ANALYZE);
        requireBoundSource(request == null ? null
                : request.getGrantedSourceName(), principal);
        return maxComputeService.preflight(
                principal.workspaceId(), request);
    }

    @PostMapping("/tools/maxcompute/diagnose")
    public MaxComputeDiagnosticView maxComputeDiagnose(
            @RequestBody MaxComputeDiagnosticRequest request,
            @RequestAttribute(
                    AgentGatewayAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
            AiGatewayPrincipal principal) {
        rateLimiter.requireAllowed(
                principal, "rest.maxcompute.preflight", true);
        principal.requireScope(AgentGatewayScope.MAXCOMPUTE_ANALYZE);
        return maxComputeService.diagnose(
                principal.workspaceId(), request);
    }

    private static void addTool(
            AiGatewayPrincipal principal,
            List<Map<String, String>> tools,
            AgentGatewayScope scope,
            String name,
            String description) {
        if (principal.scopes().contains(scope)) {
            tools.add(Map.of("name", name,
                    "description", description,
                    "scope", scope.wireName()));
        }
    }

    private static void requireBoundSource(
            String sourceName, AiGatewayPrincipal principal) {
        if (sourceName == null
                || !principal.grantedSourceName().equals(sourceName.trim())) {
            throw new IllegalArgumentException(
                    "Gateway 工具只能使用令牌绑定的逻辑数据源");
        }
    }
}
