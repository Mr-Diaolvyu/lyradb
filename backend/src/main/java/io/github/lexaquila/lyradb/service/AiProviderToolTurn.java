package io.github.lexaquila.lyradb.service;

import java.util.List;
import java.util.Map;

/** OpenAI-compatible 工具调用的一轮结构化响应。 */
public record AiProviderToolTurn(
        String content,
        List<ToolCall> toolCalls,
        Usage usage,
        Map<String, Object> assistantMessage) {

    public AiProviderToolTurn {
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
        usage = usage == null ? new Usage(0, 0, 0) : usage;
        assistantMessage = Map.copyOf(
                assistantMessage == null ? Map.of() : assistantMessage);
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("模型响应既没有正文也没有工具调用");
        }
    }

    public record ToolCall(
            String id,
            String name,
            String argumentsJson) {
        public ToolCall {
            if (id == null || id.isBlank()
                    || name == null || name.isBlank()) {
                throw new IllegalArgumentException("工具调用标识和名称不能为空");
            }
            argumentsJson = argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson;
            if (argumentsJson.length() > 100_000) {
                throw new IllegalArgumentException("工具参数超过安全上限");
            }
        }
    }

    public record Usage(
            long promptTokens,
            long completionTokens,
            long totalTokens) {
        public Usage {
            if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("Token 用量不能为负数");
            }
        }
    }
}
