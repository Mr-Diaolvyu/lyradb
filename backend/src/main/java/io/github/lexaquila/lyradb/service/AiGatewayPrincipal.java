package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

/** 经令牌验证且绑定到单一工作空间、用户和 Grant 的独立身份。 */
public record AiGatewayPrincipal(
        String tokenId,
        String username,
        String userId,
        String workspaceId,
        String grantId,
        String grantedSourceName,
        Set<AgentGatewayScope> scopes) {

    public AiGatewayPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public void requireScope(AgentGatewayScope scope) {
        if (!scopes.contains(scope)) {
            throw new AccessDeniedException(
                    "Gateway 身份缺少权限: " + scope.wireName());
        }
    }
}
