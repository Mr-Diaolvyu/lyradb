package io.github.lexaquila.lyradb.service;

/** OpenAI-compatible 普通对话正文与本轮 Token 用量。 */
public record AiProviderChatResult(
        String content,
        AiProviderToolTurn.Usage usage) {

    public AiProviderChatResult {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型响应正文不能为空");
        }
        usage = usage == null
                ? new AiProviderToolTurn.Usage(0, 0, 0) : usage;
    }
}
