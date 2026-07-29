package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台任务状态 WebSocket。
 *
 * <p>企业版握手必须携带已认证 Principal 和已选择工作空间；通知只发送给
 * 任务所有者在同一工作空间中的会话，不再全局广播。</p>
 */
public class TaskWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties appProperties;
    private final Map<WebSocketSession, Subscription> sessions = new ConcurrentHashMap<>();

    public TaskWebSocketHandler(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        boolean enterprise = "enterprise".equalsIgnoreCase(appProperties.getEdition());
        Principal principal = session.getPrincipal();
        String username = principal != null ? principal.getName() : null;
        Object selected = session.getAttributes().get("currentWorkspaceId");
        String workspaceId = selected != null ? selected.toString() : null;

        if (enterprise && (username == null || username.isBlank()
                || workspaceId == null || workspaceId.isBlank())) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("需要登录并选择工作空间"));
            return;
        }
        sessions.put(session, new Subscription(
                username != null ? username : "personal",
                enterprise ? workspaceId : "personal"));
        log.info("任务通知 WebSocket 已连接: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("任务通知 WebSocket 已关闭: {} ({})", session.getId(), status);
    }

    public void sendTaskUpdate(String ownerUsername, String workspaceId, String taskId,
            String status, long totalRows, long elapsedMs, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        payload.put("totalRows", totalRows);
        payload.put("elapsedMs", elapsedMs);
        payload.put("message", message);

        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("序列化任务通知失败: {}", e.getClass().getSimpleName());
            return;
        }

        TextMessage msg = new TextMessage(json);
        for (Map.Entry<WebSocketSession, Subscription> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getKey();
            Subscription subscription = entry.getValue();
            if (!subscription.matches(ownerUsername, workspaceId) || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(msg);
                }
            } catch (IOException e) {
                sessions.remove(session);
                log.warn("发送任务通知失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    private record Subscription(String ownerUsername, String workspaceId) {
        boolean matches(String owner, String workspace) {
            return ownerUsername.equals(owner)
                    && workspaceId != null
                    && !workspaceId.isBlank()
                    && workspaceId.equals(workspace);
        }
    }
}
