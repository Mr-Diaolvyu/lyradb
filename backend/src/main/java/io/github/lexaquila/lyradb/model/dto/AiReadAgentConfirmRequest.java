package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** 用户确认时必须原样回传计划摘要，防止所见计划与执行计划不一致。 */
@Data
public class AiReadAgentConfirmRequest {
    private String planSha256;
}
