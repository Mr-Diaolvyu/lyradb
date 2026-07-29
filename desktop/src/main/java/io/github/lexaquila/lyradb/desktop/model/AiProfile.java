package io.github.lexaquila.lyradb.desktop.model;

import java.util.Objects;

/**
 * 个人桌面版 AI Provider 配置。
 *
 * <p>API Key 只在内存中以明文存在，持久化时由本地凭据保险箱加密。</p>
 */
public final class AiProfile {

    private String providerKey = "deepseek";
    private String displayName = "DeepSeek";
    private String baseUrl = "https://api.deepseek.com/v1";
    private String model = "deepseek-chat";
    private String apiKey = "";
    private double temperature = 0.2D;
    private int maxTokens = 4096;

    public AiProfile() {
    }

    public AiProfile copy() {
        AiProfile copy = new AiProfile();
        copy.providerKey = providerKey;
        copy.displayName = displayName;
        copy.baseUrl = baseUrl;
        copy.model = model;
        copy.apiKey = apiKey;
        copy.temperature = temperature;
        copy.maxTokens = maxTokens;
        return copy;
    }

    public boolean isConfigured() {
        boolean keyOptional = "ollama".equalsIgnoreCase(providerKey);
        return !baseUrl.isBlank() && !model.isBlank()
                && (keyOptional || !apiKey.isBlank());
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = clean(providerKey);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = clean(displayName);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = clean(baseUrl);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = clean(model);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = Objects.requireNonNullElse(apiKey, "");
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = Math.max(0D, Math.min(2D, temperature));
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(256, Math.min(32768, maxTokens));
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
