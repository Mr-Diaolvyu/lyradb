package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticView;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightView;
import io.github.lexaquila.lyradb.service.MaxComputeIntelligenceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MaxCompute Intelligence Agent 的只读专项工具。 */
@RestController
@RequestMapping("/ai/maxcompute")
public class MaxComputeAiController {

    private final MaxComputeIntelligenceService service;
    private final SecurityUtil securityUtil;

    public MaxComputeAiController(
            MaxComputeIntelligenceService service,
            SecurityUtil securityUtil) {
        this.service = service;
        this.securityUtil = securityUtil;
    }

    @PostMapping("/preflight")
    public MaxComputePreflightView preflight(
            @RequestBody MaxComputePreflightRequest request,
            HttpSession session) {
        return service.preflight(
                securityUtil.requireCurrentWorkspace(session), request);
    }

    @PostMapping("/diagnose")
    public MaxComputeDiagnosticView diagnose(
            @RequestBody MaxComputeDiagnosticRequest request,
            HttpSession session) {
        return service.diagnose(
                securityUtil.requireCurrentWorkspace(session), request);
    }
}
