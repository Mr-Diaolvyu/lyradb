package io.github.lexaquila.lyradb.model.dto;

/** 一次模型工具提议的可审计摘要，不包含密钥或数据正文。 */
public record AiAgentToolTraceView(
        int step,
        String callId,
        String toolName,
        String decision,
        String detail) {
}
