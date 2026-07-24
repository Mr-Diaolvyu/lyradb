package io.github.lexaquila.lyradb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS跨域配置
 *
 * <p>
 * 允许前端Vue开发服务器（默认 http://localhost:5173）访问后端API。
 * 生产环境可通过application.yml的app.cors配置限制来源。
 * </p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;

    public CorsConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        AppProperties.Cors cors = appProperties.getCors();
        registry.addMapping("/**")
                .allowedOriginPatterns(cors.getAllowedOrigins().split(","))
                .allowedMethods(cors.getAllowedMethods().split(","))
                .allowedHeaders(cors.getAllowedHeaders().split(","))
                .allowCredentials(true)
                .maxAge(3600);
    }
}
