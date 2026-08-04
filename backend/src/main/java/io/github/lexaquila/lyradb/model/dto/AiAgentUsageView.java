package io.github.lexaquila.lyradb.model.dto;

/** 本次编排累计的模型 Token 用量。 */
public record AiAgentUsageView(
        long promptTokens,
        long completionTokens,
        long totalTokens) {
}
