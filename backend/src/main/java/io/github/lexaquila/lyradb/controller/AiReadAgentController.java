package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.AiReadAgentCancelView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentConfirmRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentExecutionView;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.service.GovernedReadAgentService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受治理只读 Agent 的预检、确认执行与取消 API。 */
@RestController
@RequestMapping("/ai/agent/read")
public class AiReadAgentController {

    private final GovernedReadAgentService agentService;
    private final SecurityUtil securityUtil;

    public AiReadAgentController(
            GovernedReadAgentService agentService,
            SecurityUtil securityUtil) {
        this.agentService = agentService;
        this.securityUtil = securityUtil;
    }

    @PostMapping("/plans")
    public AiReadAgentPlanView plan(
            @RequestBody AiReadAgentPlanRequest request,
            HttpSession session) {
        return agentService.plan(
                securityUtil.requireCurrentWorkspace(session), request);
    }

    @PostMapping("/plans/{runId}/execute")
    public AiReadAgentExecutionView execute(
            @PathVariable String runId,
            @RequestBody AiReadAgentConfirmRequest request,
            HttpSession session) {
        if (request == null) {
            throw new IllegalArgumentException("确认请求不能为空");
        }
        return agentService.execute(
                securityUtil.requireCurrentWorkspace(session), runId,
                request.getPlanSha256());
    }

    @PostMapping("/plans/{runId}/cancel")
    public AiReadAgentCancelView cancel(
            @PathVariable String runId, HttpSession session) {
        return agentService.cancel(
                securityUtil.requireCurrentWorkspace(session), runId);
    }
}
