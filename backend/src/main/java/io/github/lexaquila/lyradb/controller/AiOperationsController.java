package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiOperationsService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AI 运行态指标；仅数据管理员、治理人员和审计员可见。 */
@RestController
@RequestMapping("/ai/operations")
public class AiOperationsController {

    private final AiOperationsService service;
    private final SecurityUtil securityUtil;

    public AiOperationsController(
            AiOperationsService service, SecurityUtil securityUtil) {
        this.service = service;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpSession session) {
        if (!securityUtil.hasRole("DS_ADMIN")
                && !securityUtil.hasRole("STEWARD")
                && !securityUtil.hasRole("AUDITOR")) {
            throw new AccessDeniedException(
                    "AI 运维指标仅限管理员、治理人员或审计员");
        }
        return service.snapshot(
                securityUtil.requireCurrentWorkspace(session));
    }
}
