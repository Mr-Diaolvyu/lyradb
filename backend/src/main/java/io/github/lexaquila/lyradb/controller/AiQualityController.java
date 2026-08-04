package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.AiQualityAutoEvaluationRequest;
import io.github.lexaquila.lyradb.model.dto.AiQualityDashboardView;
import io.github.lexaquila.lyradb.model.dto.AiQualityEvaluationRequest;
import io.github.lexaquila.lyradb.model.dto.AiQualityRunView;
import io.github.lexaquila.lyradb.service.AiQualityService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 可信 AI 黄金集与质量仪表 API。 */
@RestController
@RequestMapping("/ai/quality")
public class AiQualityController {

    private final AiQualityService qualityService;
    private final SecurityUtil securityUtil;

    public AiQualityController(
            AiQualityService qualityService, SecurityUtil securityUtil) {
        this.qualityService = qualityService;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/dashboard")
    public AiQualityDashboardView dashboard(HttpSession session) {
        return qualityService.dashboard(
                securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping("/evaluate")
    public AiQualityRunView evaluate(
            @RequestBody AiQualityEvaluationRequest request,
            HttpSession session) {
        return qualityService.evaluate(
                securityUtil.requireCurrentWorkspace(session), request);
    }

    /** 自动调用当前工作空间默认 Provider 跑完整黄金集。 */
    @PostMapping("/evaluate/auto")
    public AiQualityRunView evaluateAutomatically(
            @RequestBody AiQualityAutoEvaluationRequest request,
            HttpSession session) {
        return qualityService.evaluateAutomatically(
                securityUtil.requireCurrentWorkspace(session), request);
    }
}
