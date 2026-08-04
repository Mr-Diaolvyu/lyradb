package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/** 为已有只读 Grant 签发独立 Gateway 身份。 */
@Data
public class AiGatewayTokenIssueRequest {
    private String displayName;
    private String grantId;
    private Set<AgentGatewayScope> scopes;
    private LocalDateTime expiresAt;
}
