package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** 知识审核动作：VERIFY、REJECT 或 RETIRE。 */
@Data
public class AiKnowledgeReviewRequest {
    private String decision;
    private String comment;
}
