package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.driver.DriverDownloadWebSocketHandler;
import io.github.lexaquila.lyradb.service.TaskWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 *
 * <p>
 * 端点（受 context-path 影响，实际带 {@code /api} 前缀）：
 * <ul>
 * <li>{@code /ws/drivers} - 驱动下载进度推送（PRD T1 缓解：可恢复下载 + 进度反馈）</li>
 * <li>{@code /ws/tasks} - 后台查询任务状态推送（迭代二 E1）</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Bean
    public DriverDownloadWebSocketHandler driverDownloadWebSocketHandler() {
        return new DriverDownloadWebSocketHandler();
    }

    @Bean
    public TaskWebSocketHandler taskWebSocketHandler() {
        return new TaskWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(driverDownloadWebSocketHandler(), "/ws/drivers")
                .setAllowedOrigins("*");
        registry.addHandler(taskWebSocketHandler(), "/ws/tasks")
                .setAllowedOrigins("*");
    }
}
