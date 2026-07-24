package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.driver.DriverDownloadWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 *
 * <p>
 * 用于驱动下载进度的实时推送（PRD T1 缓解：可恢复下载 + 进度反馈）。
 * 端点：{@code /ws/drivers}（受 context-path 影响，实际为 {@code /api/ws/drivers}）。
 * </p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Bean
    public DriverDownloadWebSocketHandler driverDownloadWebSocketHandler() {
        return new DriverDownloadWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(driverDownloadWebSocketHandler(), "/ws/drivers")
                .setAllowedOrigins("*");
    }
}
