package io.github.lexaquila.lyradb.service;

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
 * 后台任务状态 WebSocket 处理器（迭代二 E1）
 *
 * <p>
 * 端点 {@code /ws/tasks}（实际 {@code /api/ws/tasks}），任务状态变更时向所有订阅者广播：
 * {@code {"taskId":"...","status":"RUNNING|DONE|ERROR|CANCELLED","totalRows":100,"elapsedMs":1234,"message":"..."}}。
 * </p>
 */
public class TaskWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("任务通知 WebSocket 已连接: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("任务通知 WebSocket 已关闭: {} ({})", session.getId(), status);
    }

    /**
     * 广播任务状态变更
     */
    public void sendTaskUpdate(String taskId, String status, long totalRows, long elapsedMs, String message) {
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
                    log.warn("发送任务通知失败: {}", e.getMessage());
                }
            }
        }
    }
}
