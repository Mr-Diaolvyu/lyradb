package io.github.lexaquila.lyradb.config;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仅供安全过滤链测试使用的探针端点。
 */
@TestComponent
@RestController
class SecurityBoundaryProbeController {

    @GetMapping({
            "/connections", "/query", "/metadata", "/history",
            "/migration", "/tasks", "/reports"
    })
    Map<String, Object> personalEndpoint() {
        return Map.of("success", true);
    }

    @GetMapping({
            "/auth/probe", "/admin/probe", "/approvals/probe",
            "/audit/probe", "/grants/probe", "/ent/probe", "/ai/probe",
            "/agent-gateway/probe"
    })
    Map<String, Object> enterpriseEndpoint() {
        return Map.of("success", true);
    }

    @GetMapping("/app/info")
    Map<String, Object> publicInfo() {
        return Map.of("success", true);
    }

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName());
    }

    @PostMapping("/auth/login")
    Map<String, Object> permittedWriteEndpoint() {
        return Map.of("success", true);
    }
}
