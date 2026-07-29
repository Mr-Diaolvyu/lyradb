package io.github.lexaquila.lyradb.config;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 根静态上下文的浏览器安全响应头。
 *
 * <p>该上下文不经过 Spring Security，因此必须在 Tomcat Context 内独立注册。
 * WebSocket 仅允许连接当前页面的精确 Host，Host 在拼入 CSP 前按严格字符集校验。</p>
 */
public final class FrontendSecurityHeadersFilter implements Filter {

    private static final Pattern SAFE_HOST = Pattern.compile(
            "(?:\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::\\d{1,5})?");
    private static final Pattern HASHED_ASSET = Pattern.compile(
            "^/assets/.+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String host = safeHost(httpRequest);
        httpResponse.setHeader("Content-Security-Policy", contentSecurityPolicy(host));
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Referrer-Policy", "no-referrer");
        httpResponse.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=()");
        if (httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        httpResponse.setHeader("Cache-Control", cacheControl(httpRequest));
        chain.doFilter(request, response);
    }

    static String contentSecurityPolicy(String host) {
        return "default-src 'self'; "
                + "script-src 'self'; "
                + "connect-src 'self' ws://" + host + " wss://" + host + "; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; "
                + "font-src 'self' data:; "
                + "worker-src 'self' blob:; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "frame-ancestors 'none'; "
                + "form-action 'self'";
    }

    private static String safeHost(HttpServletRequest request) {
        String header = request.getHeader("Host");
        if (header != null && SAFE_HOST.matcher(header).matches()) {
            return header;
        }

        String serverName = request.getServerName();
        int port = request.getServerPort();
        if (serverName != null && SAFE_HOST.matcher(serverName).matches()
                && port >= 1 && port <= 65535) {
            return serverName + ":" + port;
        }
        return "127.0.0.1:" + (port >= 1 && port <= 65535 ? port : 8080);
    }

    private static String cacheControl(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.equals("/") || path.equals("/index.html")
                || path.equals("/theme-init.js")) {
            return "no-store";
        }
        if (HASHED_ASSET.matcher(path).matches()) {
            return "public, max-age=31536000, immutable";
        }
        return "no-cache";
    }
}
