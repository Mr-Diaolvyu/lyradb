package io.github.lexaquila.lyradb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企业 edition 的路由隔离、CSRF 与 Cookie 安全属性回归。
 */
@WebMvcTest(
        controllers = SecurityBoundaryProbeController.class,
        properties = {
                "app.edition=enterprise",
                "app.enterprise.cookie-secure=true"
        })
@Import({SecurityConfig.class, AppProperties.class, SecurityBoundaryProbeController.class})
class EnterpriseEditionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DbUserDetailsService dbUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void enterpriseEditionDeniesEveryPersonalEndpoint() throws Exception {
        User user = new User();
        user.setUsername("tester");
        user.setCredentialVersion(7L);
        when(userRepository.findByUsername("tester"))
                .thenReturn(Optional.of(user));
        for (String path : new String[]{
                "/connections", "/query", "/metadata", "/history",
                "/migration", "/tasks", "/reports"}) {
            mockMvc.perform(get(path).with(
                            authenticatedRequest(7L)))
                    .andExpect(status().isForbidden());
        }
    }

    private static RequestPostProcessor authenticatedRequest(
            long credentialVersion) {
        return request -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "tester", "N/A", List.of()));
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY,
                    context);
            request.getSession().setAttribute(
                    SecurityUtil.CREDENTIAL_VERSION,
                    credentialVersion);
            return request;
        };
    }

    @Test
    void enterpriseEndpointsRequireAuthenticationButPublicInfoDoesNot() throws Exception {
        mockMvc.perform(get("/auth/probe"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/app/info"))
                .andExpect(status().isOk());
    }

    @Test
    void missingCsrfIsDeniedAndIssuedCookieIsSecure() throws Exception {
        mockMvc.perform(post("/auth/login"))
                .andExpect(status().isForbidden());

        MvcResult result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Set-Cookie", containsString("Secure")))
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, String> tokenBody = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), Map.class);
        String token = tokenBody.get("token");
        String headerName = tokenBody.get("headerName");
        // MockHttpServletResponse 不会把 Servlet Cookie 自定义 attribute
        // 序列化进 Set-Cookie 文本，但 Cookie 对象仍必须保留该策略。
        assertEquals("Strict", result.getResponse()
                .getCookie("XSRF-TOKEN").getAttribute("SameSite"));

        mockMvc.perform(post("/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header(headerName, token))
                .andExpect(status().isOk());
    }
}
