package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiGatewayRateLimiterTest {

    @Test
    void perTokenAndRouteLimitFailsClosed() {
        AppProperties properties = new AppProperties();
        properties.getAi().setGatewayRequestsPerMinute(2);
        AiGatewayRateLimiter limiter =
                new AiGatewayRateLimiter(
                        properties, new AiOperationalMetrics());
        AiGatewayPrincipal principal = new AiGatewayPrincipal(
                "token-1", "agent", "user-1", "workspace-1",
                "grant-1", "sales-source",
                Set.of(AgentGatewayScope.KNOWLEDGE_READ));

        assertDoesNotThrow(() -> limiter.requireAllowed(
                principal, "knowledge.search", false));
        assertDoesNotThrow(() -> limiter.requireAllowed(
                principal, "knowledge.search", false));
        assertThrows(AiGatewayRateLimitException.class,
                () -> limiter.requireAllowed(
                        principal, "knowledge.search", false));
        assertDoesNotThrow(() -> limiter.requireAllowed(
                principal, "tools.list", false));
    }
}
