package io.github.lexaquila.lyradb.ai.tool;

import java.util.Set;

/** 一次工具调用的最小可授权描述；参数正文由宿主保存，协议仅携带摘要。 */
public record AgentToolCall(
        String callId,
        String runId,
        String toolName,
        Set<String> resources,
        int requestedRows,
        long estimatedCostMicros,
        String argumentsSha256) {

    public AgentToolCall {
        callId = requireText(callId, "调用 ID", 128);
        runId = requireText(runId, "运行 ID", 128);
        toolName = requireText(toolName, "工具名", 120);
        resources = Set.copyOf(resources == null ? Set.of() : resources);
        if (resources.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("工具资源不得包含空值");
        }
        if (requestedRows < 0) {
            throw new IllegalArgumentException("请求行数不能为负数");
        }
        if (estimatedCostMicros < 0) {
            throw new IllegalArgumentException("预估成本不能为负数");
        }
        argumentsSha256 = requireText(argumentsSha256, "参数摘要", 64);
        if (!argumentsSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("参数摘要必须是 64 位 SHA-256");
        }
        argumentsSha256 = argumentsSha256.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }
}
