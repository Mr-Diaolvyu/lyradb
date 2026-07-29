package io.github.lexaquila.lyradb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 与 MVC 共用的唯一凭据型 CORS 来源。
 */
@Configuration
public class CorsConfig {

    private final AppProperties appProperties;

    public CorsConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Bean 名必须为 corsConfigurationSource，确保 SecurityFilterChain 中的
     * {@code http.cors()} 使用本配置，而不是退回 MVC 映射探测。
     */
    @Bean(name = "corsConfigurationSource")
    public CorsConfigurationSource corsConfigurationSource() {
        AppProperties.Cors configured = appProperties.getCors();
        List<String> origins = split(configured.getAllowedOrigins());
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "启用凭据时禁止配置通配 CORS 来源");
        }

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(split(configured.getAllowedMethods()));
        cors.setAllowedHeaders(split(configured.getAllowedHeaders()));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
