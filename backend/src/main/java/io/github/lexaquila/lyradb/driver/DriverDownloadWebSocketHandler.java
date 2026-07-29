
package io.github.lexaquila.lyradb.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动下载进度 WebSocket 处理器
 *
 * <p>
 * 维护已连接的会话集合，向所有订阅者广播下载进度：
 * {@code {"dbType":"MYSQL","percent":50,"message":"...","status":"progress|done|error"}}。
 * </p>
 */
public class DriverDownloadWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DriverDownloadWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public DriverDownloadWebSocketHandler(
            AppProperties appProperties,
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if ("enterprise".equalsIgnoreCase(appProperties.getEdition())
                && !canObserveEnterpriseDownloads(session)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(
                    "当前工作空间需要 DS_ADMIN 角色"));
            return;
        }
        sessions.add(session);
        log.info("驱动进度 WebSocket 已连接: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("驱动进度 WebSocket 已关闭: {} ({})", session.getId(), status);
    }

    private boolean canObserveEnterpriseDownloads(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        Object selected = session.getAttributes().get("currentWorkspaceId");
        if (principal == null || principal.getName() == null
                || selected == null || selected.toString().isBlank()) {
            return false;
        }
        User user = userRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return false;
        }
        List<String> platformRoles = user.getRoles();
        if (platformRoles != null && platformRoles.stream()
                .filter(java.util.Objects::nonNull)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .anyMatch("PLATFORM_ADMIN"::equals)) {
            return true;
        }
        return membershipRepository.findByUserIdAndWorkspaceId(
                        user.getId(), selected.toString())
                .map(membership -> hasRole(
                        membership.getRolesCsv(), "DS_ADMIN"))
                .orElse(false);
    }

    private static boolean hasRole(String rolesCsv, String expected) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return false;
        }
        return Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    /**
     * 向所有订阅者推送下载进度
     */
    public void sendProgress(String dbType, int percent, String message, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dbType", dbType);
        payload.put("percent", percent);
        payload.put("message", message);
        payload.put("status", status);

        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return;
        }

        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(msg);
                    }
                } catch (IOException e) {
                    log.warn("发送进度失败: {}", e.getMessage());
                }
            }
        }
    }
}
