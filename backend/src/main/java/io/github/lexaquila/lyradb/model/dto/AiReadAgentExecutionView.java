package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;

/** 只读 Agent 执行结果与证据回执。 */
public record AiReadAgentExecutionView(
        String runId,
        String status,
        QueryResult result,
        AiContextReceipt contextReceipt) {
}
