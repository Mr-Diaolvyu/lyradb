


package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * 个人版与企业版服务端安全边界。
 */
@Configuration
public class SecurityConfig {

    private static final String[] PERSONAL_ENDPOINTS = {
            "/connections/**", "/query/**", "/metadata/**", "/history/**",
            "/migration/**", "/tasks/**", "/reports/**"
    };

    private static final String[] ENTERPRISE_ENDPOINTS = {
            "/auth/**", "/admin/**", "/approvals/**", "/audit/**",
            "/grants/**", "/ent/**", "/ai/**"
    };

    private final AppProperties appProperties;
    private final DbUserDetailsService dbUserDetailsService;
    private final UserRepository userRepository;

    public SecurityConfig(AppProperties appProperties, DbUserDetailsService dbUserDetailsService,
                          UserRepository userRepository) {
        this.appProperties = appProperties;
        this.dbUserDetailsService = dbUserDetailsService;
        this.userRepository = userRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(dbUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(withDefaults())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "需要登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "无权访问")))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable());

        if (!"enterprise".equalsIgnoreCase(appProperties.getEdition())) {
            http.csrf(csrf -> csrf.disable())
                    .exceptionHandling(exceptions -> exceptions
                            .defaultAuthenticationEntryPointFor(
                                    (request, response, exception) ->
                                            writeJsonError(
                                                    response,
                                                    HttpServletResponse.SC_FORBIDDEN,
                                                    "当前发行版未开放该端点"),
                                    endpointMatcher(ENTERPRISE_ENDPOINTS)))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(ENTERPRISE_ENDPOINTS).denyAll()
                            .anyRequest().permitAll())
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable());
            return http.build();
        }

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        boolean secureCookie = appProperties.getEnterprise() != null
                && appProperties.getEnterprise().isCookieSecure();
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .sameSite("Strict")
                .secure(secureCookie));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http.exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                (request, response, exception) ->
                                        writeJsonError(
                                                response,
                                                HttpServletResponse.SC_FORBIDDEN,
                                                "当前发行版未开放该端点"),
                                endpointMatcher(PERSONAL_ENDPOINTS)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/app/info", "/auth/csrf", "/auth/login", "/error").permitAll()
                        .requestMatchers("/h2-console/**").denyAll()
                        .requestMatchers(PERSONAL_ENDPOINTS).denyAll()
                        .requestMatchers("/admin/users/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/drivers/*/download")
                        .authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authenticationProvider(authenticationProvider())
                .addFilterAfter(new CredentialVersionFilter(userRepository), SecurityContextHolderFilter.class);
        return http.build();
    }

    private static RequestMatcher endpointMatcher(String[] patterns) {
        return new OrRequestMatcher(Arrays.stream(patterns)
                .map(PathPatternRequestMatcher.withDefaults()::matcher)
                .map(RequestMatcher.class::cast)
                .toList());
    }

    private static void writeJsonError(HttpServletResponse response, int status,
                                       String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\""
                + message + "\"}");
    }

    /**
     * 每个已认证请求对比会话凭据版本。管理员重置密码后，目标用户所有旧会话
     * 会在下一次请求时立即失效。
     */
    private static final class CredentialVersionFilter extends OncePerRequestFilter {

        private final UserRepository userRepository;

        private CredentialVersionFilter(UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            HttpSession session = request.getSession(false);
            if (authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal()) && session != null) {
                User user = userRepository.findByUsername(authentication.getName()).orElse(null);
                Object sessionVersion = session.getAttribute(SecurityUtil.CREDENTIAL_VERSION);
                boolean valid = user != null && user.isEnabled()
                        && sessionVersion instanceof Number number
                        && number.longValue() == user.getCredentialVersion();
                if (!valid) {
                    try {
                        session.invalidate();
                    } catch (IllegalStateException ignored) {
                        // 会话已被并发请求注销。
                    }
                    SecurityContextHolder.clearContext();
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"success\":false,\"message\":\"会话凭据已失效，请重新登录\"}");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
