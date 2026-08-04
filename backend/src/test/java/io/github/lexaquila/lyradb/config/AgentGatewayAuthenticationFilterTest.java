package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.service.AiGatewayPrincipal;
import io.github.lexaquila.lyradb.service.AiGatewayTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentGatewayAuthenticationFilterTest {

    @Test
    void missingBearerFailsClosed() throws Exception {
        AgentGatewayAuthenticationFilter filter =
                new AgentGatewayAuthenticationFilter(
                        mock(AiGatewayTokenService.class));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent-gateway/v1/capabilities");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response,
                (req, res) -> { throw new AssertionError(); });

        assertEquals(401, response.getStatus());
    }

    @Test
    void downstreamValidationErrorIsNotRewrittenAsAuthenticationError()
            throws Exception {
        AiGatewayTokenService service =
                mock(AiGatewayTokenService.class);
        when(service.authenticate("lyra_valid")).thenReturn(
                new AiGatewayPrincipal(
                        "token-1", "agent-user", "user-1",
                        "workspace-1", "grant-1", "sales-source",
                        Set.of(AgentGatewayScope.READ_PLAN)));
        AgentGatewayAuthenticationFilter filter =
                new AgentGatewayAuthenticationFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/agent-gateway/v1/tools/read/plans");
        request.addHeader("Authorization", "Bearer lyra_valid");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class,
                () -> filter.doFilter(request, response,
                        (req, res) -> {
                            throw new IllegalArgumentException("计划无效");
                        }));
        assertEquals(200, response.getStatus());
    }
}
