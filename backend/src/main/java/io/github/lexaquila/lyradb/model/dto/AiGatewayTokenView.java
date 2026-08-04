package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;

import java.time.LocalDateTime;
import java.util.Set;

/** 不包含令牌正文的 Gateway 身份视图。 */
public record AiGatewayTokenView(
        String id,
        String displayName,
        String tokenPrefix,
        String principalUserId,
        String grantId,
        String grantedSourceName,
        Set<AgentGatewayScope> scopes,
        boolean revoked,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt) {

    public AiGatewayTokenView {
        scopes = Set.copyOf(scopes);
    }
}
