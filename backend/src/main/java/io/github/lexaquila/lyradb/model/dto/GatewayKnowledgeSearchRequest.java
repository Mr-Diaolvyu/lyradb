package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** Gateway 的已验证知识检索参数。 */
@Data
public class GatewayKnowledgeSearchRequest {
    private String question;
}
