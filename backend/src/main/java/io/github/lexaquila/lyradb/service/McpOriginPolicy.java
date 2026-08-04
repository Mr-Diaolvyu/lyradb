package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** MCP Streamable HTTP 的 DNS rebinding/跨站请求 Origin 门禁。 */
@Service
public class McpOriginPolicy {

    private final AppProperties properties;

    public McpOriginPolicy(AppProperties properties) {
        this.properties = properties;
    }

    public void validate(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        URI parsed = parseOrigin(origin);
        String normalized = normalize(parsed);
        if (normalized.equals(requestOrigin(request))
                || allowedOrigins().contains(normalized)) {
            return;
        }
        throw new AccessDeniedException("MCP Origin 不在允许列表");
    }

    private Set<String> allowedOrigins() {
        String configured = properties.getAi().getGatewayAllowedOrigins();
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(McpOriginPolicy::parseOrigin)
                .map(McpOriginPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static URI parseOrigin(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath() != null
                    && !uri.getPath().isEmpty()
                    && !"/".equals(uri.getPath())) {
                throw new IllegalArgumentException("Origin 格式无效");
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new AccessDeniedException("MCP Origin 格式无效", exception);
        }
    }

    private static String requestOrigin(HttpServletRequest request) {
        int port = request.getServerPort();
        String scheme = request.getScheme().toLowerCase(Locale.ROOT);
        boolean defaultPort = "https".equals(scheme) && port == 443
                || "http".equals(scheme) && port == 80;
        return scheme + "://"
                + request.getServerName().toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port);
    }

    private static String normalize(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = "https".equals(scheme) && port == 443
                || "http".equals(scheme) && port == 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port < 0 || defaultPort ? "" : ":" + port);
    }
}
