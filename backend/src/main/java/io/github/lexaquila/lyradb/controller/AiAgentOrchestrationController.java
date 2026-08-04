package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.AiAgentOrchestrationRequest;
import io.github.lexaquila.lyradb.model.dto.AiAgentOrchestrationView;
import io.github.lexaquila.lyradb.service.AiAgentOrchestratorService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模型工具编排入口。只允许生成只读计划，不暴露执行能力。 */
@RestController
@RequestMapping("/ai/agent")
public class AiAgentOrchestrationController {

    private final AiAgentOrchestratorService orchestratorService;
    private final SecurityUtil securityUtil;

    public AiAgentOrchestrationController(
            AiAgentOrchestratorService orchestratorService,
            SecurityUtil securityUtil) {
        this.orchestratorService = orchestratorService;
        this.securityUtil = securityUtil;
    }

    @PostMapping("/orchestrate")
    public AiAgentOrchestrationView orchestrate(
            @RequestBody AiAgentOrchestrationRequest request,
            HttpSession session) {
        return orchestratorService.orchestrate(
                securityUtil.requireCurrentWorkspace(session), request);
    }
}
