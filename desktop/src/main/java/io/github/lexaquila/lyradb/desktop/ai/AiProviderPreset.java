package io.github.lexaquila.lyradb.desktop.ai;

import java.util.List;

/**
 * OpenAI 兼容 Provider 预设。用户也可选择自定义地址与模型。
 */
public record AiProviderPreset(String key, String displayName,
                               String baseUrl, String defaultModel,
                               boolean apiKeyOptional) {

    private static final List<AiProviderPreset> PRESETS = List.of(
            new AiProviderPreset("deepseek", "DeepSeek",
                    "https://api.deepseek.com/v1", "deepseek-chat", false),
            new AiProviderPreset("dashscope", "阿里云百炼",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-plus", false),
            new AiProviderPreset("openai", "OpenAI",
                    "https://api.openai.com/v1", "gpt-4.1-mini", false),
            new AiProviderPreset("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4-flash", false),
            new AiProviderPreset("doubao", "火山方舟 / 豆包",
                    "https://ark.cn-beijing.volces.com/api/v3",
                    "请填写 Endpoint ID", false),
            new AiProviderPreset("ollama", "本地 Ollama",
                    "http://127.0.0.1:11434/v1", "qwen2.5-coder", true),
            new AiProviderPreset("custom", "自定义兼容服务",
                    "https://", "", false));

    public static List<AiProviderPreset> all() {
        return PRESETS;
    }

    public static AiProviderPreset find(String key) {
        return PRESETS.stream()
                .filter(preset -> preset.key.equalsIgnoreCase(key))
                .findFirst()
                .orElse(PRESETS.get(0));
    }

    @Override
    public String toString() {
        return displayName;
    }
}
