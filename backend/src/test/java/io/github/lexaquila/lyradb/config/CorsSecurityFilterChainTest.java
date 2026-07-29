package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Security 过滤链的 CORS 预检回归。
 */
@WebMvcTest(
        controllers = SecurityBoundaryProbeController.class,
        properties = {
                "app.edition=personal",
                "app.cors.allowed-origins=http://127.0.0.1:18083",
                "app.cors.allowed-methods=GET,POST,OPTIONS",
                "app.cors.allowed-headers=X-Test,Content-Type"
        })
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        AppProperties.class,
        SecurityBoundaryProbeController.class
})
class CorsSecurityFilterChainTest {

    private static final String ALLOWED_ORIGIN =
            "http://127.0.0.1:18083";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DbUserDetailsService dbUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void allowedOriginPreflightReturnsCredentialHeaders()
            throws Exception {
        mockMvc.perform(options("/app/info")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                "GET")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "X-Test"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ALLOWED_ORIGIN))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString("GET")))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("X-Test")));
    }

    @Test
    void untrustedOriginPreflightIsRejectedWithoutAllowOrigin()
            throws Exception {
        mockMvc.perform(options("/app/info")
                        .header(
                                HttpHeaders.ORIGIN,
                                "http://evil.example")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                "GET")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "X-Test"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
