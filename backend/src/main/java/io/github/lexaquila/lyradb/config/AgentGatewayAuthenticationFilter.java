package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.service.AiGatewayPrincipal;
import io.github.lexaquila.lyradb.service.AiGatewayTokenService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 仅处理 /agent-gateway/** 的无会话 Bearer 身份。 */
@Component
public class AgentGatewayAuthenticationFilter
        extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE =
            "lyradb.agentGatewayPrincipal";

    private final AiGatewayTokenService tokenService;

    public AgentGatewayAuthenticationFilter(
            AiGatewayTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/agent-gateway/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.regionMatches(
                true, 0, "Bearer ", 0, 7)) {
            writeUnauthorized(response);
            return;
        }
        AiGatewayPrincipal principal;
        try {
            principal = tokenService.authenticate(
                    authorization.substring(7).trim());
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        request.setAttribute(SecurityUtil.REQUEST_WORKSPACE_SNAPSHOT,
                principal.workspaceId());
        SecurityContext context =
                SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal.username(), null,
                List.of(new SimpleGrantedAuthority(
                        "ROLE_AGENT_GATEWAY"))));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void writeUnauthorized(HttpServletResponse response)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Gateway 身份无效或已失效\"}");
    }
}
