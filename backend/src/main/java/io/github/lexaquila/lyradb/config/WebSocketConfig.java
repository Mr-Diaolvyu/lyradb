
package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.driver.DriverDownloadWebSocketHandler;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import io.github.lexaquila.lyradb.service.TaskWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Arrays;

/**
 * WebSocket 配置。任务端点继承 HTTP 会话属性，Origin 与 HTTP CORS 使用同一白名单。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    public WebSocketConfig(AppProperties appProperties,
                           UserRepository userRepository,
                           WorkspaceMembershipRepository membershipRepository) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Bean
    public DriverDownloadWebSocketHandler driverDownloadWebSocketHandler() {
        return new DriverDownloadWebSocketHandler(
                appProperties, userRepository, membershipRepository);
    }

    @Bean
    public TaskWebSocketHandler taskWebSocketHandler() {
        return new TaskWebSocketHandler(appProperties);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        HttpSessionHandshakeInterceptor sessionInterceptor = new HttpSessionHandshakeInterceptor();

        WebSocketHandlerRegistration drivers = registry
                .addHandler(driverDownloadWebSocketHandler(), "/ws/drivers")
                .addInterceptors(sessionInterceptor);
        WebSocketHandlerRegistration tasks = registry
                .addHandler(taskWebSocketHandler(), "/ws/tasks")
                .addInterceptors(sessionInterceptor);

        String configured = appProperties.getCors() != null
                ? appProperties.getCors().getAllowedOrigins() : "";
        String[] origins = Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank() && !"*".equals(value))
                .toArray(String[]::new);

        if (origins.length > 0) {
            drivers.setAllowedOrigins(origins);
            tasks.setAllowedOrigins(origins);
        } else if (!"enterprise".equalsIgnoreCase(appProperties.getEdition())) {
            // 个人版开发环境仅允许本机前端，不接受任意互联网 Origin。
            String[] localPatterns = {"http://localhost:*", "http://127.0.0.1:*"};
            drivers.setAllowedOriginPatterns(localPatterns);
            tasks.setAllowedOriginPatterns(localPatterns);
        }
        // 企业版空白/通配配置不调用 setAllowedOrigins，使用框架默认同源策略。
    }
}
