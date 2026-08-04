package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.service.AiGatewayTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人 edition 不得暴露企业认证、审批、审计和管理端点。
 */
@WebMvcTest(
        controllers = SecurityBoundaryProbeController.class,
        properties = "app.edition=personal")
@Import({SecurityConfig.class, AppProperties.class, SecurityBoundaryProbeController.class})
class PersonalEditionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DbUserDetailsService dbUserDetailsService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AiGatewayTokenService aiGatewayTokenService;

    @Test
    void personalEditionDeniesEveryEnterpriseEndpoint() throws Exception {
        for (String path : new String[]{
                "/auth/probe", "/admin/probe", "/approvals/probe",
                "/audit/probe", "/grants/probe", "/ent/probe", "/ai/probe",
                "/agent-gateway/probe"}) {
            mockMvc.perform(get(path).with(authenticatedRequest()))
                    .andExpect(status().isForbidden());
        }
    }

    private static RequestPostProcessor authenticatedRequest() {
        return request -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "tester", "N/A", List.of()));
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY,
                    context);
            return request;
        };
    }

    @Test
    void personalEndpointsRemainAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/connections")).andExpect(status().isOk());
        mockMvc.perform(get("/query")).andExpect(status().isOk());
    }
}
