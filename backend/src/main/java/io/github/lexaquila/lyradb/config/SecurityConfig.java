package io.github.lexaquila.lyradb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 *
 * <p>
 * 按发行版门控：
 * </p>
 * <ul>
 * <li>{@code app.edition=personal}（默认）：全部放行，保持既有无认证体验（桌面个人版）。</li>
 * <li>{@code app.edition=enterprise}：强制认证（DB 用户 + BCrypt + 会话），
 * 放行 {@code /auth/login}、{@code /drivers/**}（首次下载驱动）、{@code /ws/**}。</li>
 * </ul>
 *
 * <p>
 * 管理员操作、数据源管理、企业查询等端点在 enterprise 下需登录；
 * 管理员端点 {@code /admin/**} 进一步要求 PLATFORM_ADMIN/DS_ADMIN 角色（控制器内校验）。
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppProperties appProperties;
    private final DbUserDetailsService dbUserDetailsService;

    public SecurityConfig(AppProperties appProperties, DbUserDetailsService dbUserDetailsService) {
        this.appProperties = appProperties;
        this.dbUserDetailsService = dbUserDetailsService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(dbUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(h -> h.frameOptions(fo -> fo.disable()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        boolean enterprise = "enterprise".equalsIgnoreCase(appProperties.getEdition());
        if (!enterprise) {
            // 个人版：全部放行
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        // 企业版：除放行端点外需登录
        http.authorizeHttpRequests(a -> a
                .requestMatchers(
                        "/app/**",
                        "/auth/login", "/auth/logout",
                        "/drivers/**",
                        "/ws/**")
                .permitAll()
                .anyRequest().authenticated())
                .formLogin(f -> f.disable())
                .httpBasic(b -> {
                });
        http.authenticationProvider(authenticationProvider());
        return http.build();
    }
}
