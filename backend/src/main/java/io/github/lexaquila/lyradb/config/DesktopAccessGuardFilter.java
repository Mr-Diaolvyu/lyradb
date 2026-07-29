
package io.github.lexaquila.lyradb.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 桌面版本机访问门禁。
 *
 * <p>首次访问只能通过托盘签发的一次性令牌完成交换。交换后，API 请求必须同时
 * 携带已授权的 HttpOnly Session 与页面内存中的会话证明；WebSocket 还必须携带
 * 同一证明及与桌面监听端口一致的 Origin。该过滤器位于 Spring Security 之前，
 * 对所有请求先执行本机来源限制。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.desktop.tray-enabled", havingValue = "true")
public class DesktopAccessGuardFilter extends OncePerRequestFilter {

    static final String SESSION_AUTHORIZED =
            DesktopAccessGuardFilter.class.getName() + ".AUTHORIZED";
    static final String SESSION_PROOF_DIGEST =
            DesktopAccessGuardFilter.class.getName() + ".PROOF_DIGEST";
    public static final String BOOTSTRAP_PATH = "/desktop/bootstrap";
    public static final String PROOF_HEADER = "X-LyraDB-Desktop-Token";
    public static final String WEBSOCKET_PROOF_QUERY = "desktop_token";
    public static final String PROOF_FRAGMENT = "desktop_token";

    private final DesktopAccessTokenService tokenService;

    public DesktopAccessGuardFilter(DesktopAccessTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (!isLoopback(request.getRemoteAddr())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "桌面服务仅允许本机访问");
            return;
        }

        HttpSession existingSession = request.getSession(false);
        String requestPath = pathWithoutContext(request);
        if ("GET".equalsIgnoreCase(request.getMethod())
                && BOOTSTRAP_PATH.equals(requestPath)
                && tokenService.consume(request.getParameter("token"))) {
            HttpSession session = existingSession;
            if (session == null) {
                session = request.getSession(true);
            } else {
                request.changeSessionId();
            }
            String proof = tokenService.issueSessionProof();
            session.setAttribute(SESSION_AUTHORIZED, Boolean.TRUE);
            session.setAttribute(SESSION_PROOF_DIGEST, tokenService.digest(proof));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.sendRedirect(cleanApplicationUrl(proof));
            return;
        }

        // 应用版本信息不包含用户数据，供 SPA 在 bootstrap 前判断运行形态。
        if ("GET".equalsIgnoreCase(request.getMethod())
                && "/app/info".equals(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (existingSession == null
                || !Boolean.TRUE.equals(existingSession.getAttribute(SESSION_AUTHORIZED))
                || !proofMatches(request, existingSession, requestPath)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "桌面会话证明缺失或无效，请从 LyraDB 桌面托盘重新打开应用");
            return;
        }
        if (isWebSocketPath(requestPath) && !hasExactDesktopOrigin(request)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "WebSocket Origin 与桌面服务端口不匹配");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private boolean proofMatches(HttpServletRequest request, HttpSession session,
                                 String requestPath) {
        Object stored = session.getAttribute(SESSION_PROOF_DIGEST);
        if (!(stored instanceof byte[] expected)) {
            return false;
        }
        String candidate = isWebSocketPath(requestPath)
                ? request.getParameter(WEBSOCKET_PROOF_QUERY)
                : request.getHeader(PROOF_HEADER);
        return candidate != null && !candidate.isBlank()
                && MessageDigest.isEqual(expected, tokenService.digest(candidate));
    }

    private static boolean isWebSocketPath(String path) {
        return path != null && path.startsWith("/ws/");
    }

    private static boolean hasExactDesktopOrigin(HttpServletRequest request) {
        String value = request.getHeader(HttpHeaders.ORIGIN);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI origin = URI.create(value);
            return "http".equalsIgnoreCase(origin.getScheme())
                    && "127.0.0.1".equals(origin.getHost())
                    && origin.getPort() == request.getLocalPort()
                    && (origin.getPath() == null || origin.getPath().isEmpty());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String cleanApplicationUrl(String proof) {
        return "/#" + PROOF_FRAGMENT + "=" + proof;
    }

    private static boolean isLoopback(String remoteAddress) {
        try {
            return remoteAddress != null
                    && InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
