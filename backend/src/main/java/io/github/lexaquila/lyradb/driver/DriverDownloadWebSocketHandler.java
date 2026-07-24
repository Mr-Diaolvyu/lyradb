package io.github.lexaquila.lyradb.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("驱动进度 WebSocket 已连接: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("驱动进度 WebSocket 已关闭: {} ({})", session.getId(), status);
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
