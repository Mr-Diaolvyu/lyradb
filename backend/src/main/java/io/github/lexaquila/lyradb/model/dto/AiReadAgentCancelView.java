package io.github.lexaquila.lyradb.model.dto;

/** 计划取消或运行中断请求的结果。 */
public record AiReadAgentCancelView(
        String runId,
        String status,
        boolean databaseCancellationDispatched) {
}
