package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.repository.AiProviderConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Provider 服务 + OpenAI-compatible 聊天客户端
 *
 * <p>KEY 加密存储；聊天时解密、内存短暂使用、用后置空。</p>
 */
@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    private static final Map<String, Map<String, String>> PRESETS = new LinkedHashMap<>() {{
        put("bailian", Map.of("displayName", "阿里云百炼", "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "model", "qwen-plus"));
        put("glm", Map.of("displayName", "智谱GLM", "baseUrl", "https://open.bigmodel.cn/api/paas/v4", "model", "glm-4-flash"));
        put("doubao", Map.of("displayName", "火山豆包", "baseUrl", "https://ark.cn-beijing.volces.com/api/v3", "model", "doubao-pro-32k"));
        put("deepseek", Map.of("displayName", "DeepSeek", "baseUrl", "https://api.deepseek.com/v1", "model", "deepseek-chat"));
        put("gpt", Map.of("displayName", "OpenAI GPT", "baseUrl", "https://api.openai.com/v1", "model", "gpt-4o-mini"));
        put("custom", Map.of("displayName", "自定义", "baseUrl", "", "model", ""));
    }};

    private final AiProviderConfigRepository repository;
    private final CredentialService credentialService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public AiProviderService(AiProviderConfigRepository repository, CredentialService credentialService) {
        this.repository = repository;
        this.credentialService = credentialService;
    }

    public Map<String, Map<String, String>> presets() {
        return PRESETS;
    }

    /** 列出（apiKey 掩码） */
    public List<Map<String, Object>> listMasked(String workspaceId) {
        List<AiProviderConfig> all = repository
                .findByWorkspaceIdOrWorkspaceIdNullOrderByIsDefaultDescCreatedAtDesc(workspaceId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProviderConfig c : all) {
            out.add(toMaskedView(c));
        }
        return out;
    }

    public AiProviderConfig create(String workspaceId, String providerKey, String displayName,
                                   String baseUrl, String apiKey, String model,
                                   Double temperature, Integer maxTokens, boolean isDefault) {
        AiProviderConfig c = new AiProviderConfig();
        c.setWorkspaceId(workspaceId);
        c.setProviderKey(providerKey);
        c.setDisplayName(displayName);
        c.setBaseUrl(baseUrl);
        c.setModel(model);
        c.setTemperature(temperature != null ? temperature : 0.2);
        c.setMaxTokens(maxTokens != null ? maxTokens : 2048);
        c.setEnabled(true);
        c.setDefault(isDefault);
        // 加密 KEY
        Map<String, Object> enc = credentialService.encryptSensitiveFields(Map.of("apiKey", apiKey));
        c.setApiKey((String) enc.get("apiKey"));
        if (isDefault) {
            // 同工作空间内只保留一个默认
            repository.findByWorkspaceIdOrWorkspaceIdNullOrderByIsDefaultDescCreatedAtDesc(workspaceId)
                    .stream().filter(AiProviderConfig::isDefault).forEach(o -> { o.setDefault(false); repository.save(o); });
        }
        return repository.save(c);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    public void setDefault(String id) {
        AiProviderConfig target = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Provider 不存在: " + id));
        for (AiProviderConfig c : repository.findAll()) {
            c.setDefault(c.getId().equals(id));
            repository.save(c);
        }
        target.setDefault(true);
        repository.save(target);
    }

    /** 取默认 Provider 并解密 KEY（内存使用） */
    public AiProviderConfig resolveDefault() {
        return repository.findByIsDefaultTrueAndEnabledTrue()
                .orElseThrow(() -> new RuntimeException("未配置默认 AI Provider，请联系管理员在「管理-AI」中配置"));
    }

    private String decryptKey(AiProviderConfig c) {
        Map<String, Object> dec = credentialService.decryptSensitiveFields(Map.of("apiKey", c.getApiKey()));
        return (String) dec.get("apiKey");
    }

    /**
     * OpenAI-compatible 聊天调用
     *
     * @return 模型回复文本
     */
    public String chat(AiProviderConfig config, List<Map<String, String>> messages) {
        String key = decryptKey(config);
        try {
            String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(key);

            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("messages", messages);
            body.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.2);
            if (config.getMaxTokens() != null) body.put("max_tokens", config.getMaxTokens());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            @SuppressWarnings("rawtypes")
            org.springframework.http.ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
            if (resp.getBody() == null) {
                throw new RuntimeException("AI 返回为空");
            }
            Object choices = resp.getBody().get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object message = m.get("message");
                    if (message instanceof Map<?, ?> mm) {
                        Object content = mm.get("content");
                        return content != null ? content.toString() : "";
                    }
                }
            }
            throw new RuntimeException("AI 返回格式异常");
        } catch (Exception e) {
            log.error("AI 调用失败: {} - {}", config.getDisplayName(), e.getMessage());
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式 OpenAI-compatible 聊天：读取 SSE 流，逐 delta 回调
     *
     * @param onDelta   每收到一段增量文本回调
     * @param onDone    流结束（[DONE] 或 EOF）
     * @param onError   异常
     */
    public void streamChat(AiProviderConfig config, List<Map<String, String>> messages,
                           java.util.function.Consumer<String> onDelta,
                           Runnable onDone,
                           java.util.function.Consumer<Exception> onError) {
        String key = decryptKey(config);
        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(key);
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.2);
            if (config.getMaxTokens() != null) body.put("max_tokens", config.getMaxTokens());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            restTemplate.execute(url, org.springframework.http.HttpMethod.POST,
                    req -> {
                        req.getHeaders().putAll(headers);
                        req.getBody().write(objectMapper.writeValueAsBytes(body));
                    },
                    resp -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(resp.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String data = line.substring(5).trim();
                                if (data.isEmpty()) continue;
                                if ("[DONE]".equals(data)) break;
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> frame = objectMapper.readValue(data, Map.class);
                                    Object choices = frame.get("choices");
                                    if (choices instanceof List<?> list && !list.isEmpty()
                                            && list.get(0) instanceof Map<?, ?> m
                                            && m.get("delta") instanceof Map<?, ?> d
                                            && d.get("content") != null) {
                                        onDelta.accept(d.get("content").toString());
                                    }
                                } catch (Exception ignored) {
                                    // 单帧解析失败跳过
                                }
                            }
                        }
                        onDone.run();
                        return null;
                    });
        } catch (Exception e) {
            log.error("AI 流式调用失败: {} - {}", config.getDisplayName(), e.getMessage());
            onError.accept(e);
        }
    }

    private Map<String, Object> toMaskedView(AiProviderConfig c) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", c.getId());
        v.put("providerKey", c.getProviderKey());
        v.put("displayName", c.getDisplayName());
        v.put("baseUrl", c.getBaseUrl());
        v.put("model", c.getModel());
        v.put("temperature", c.getTemperature());
        v.put("maxTokens", c.getMaxTokens());
        v.put("enabled", c.isEnabled());
        v.put("isDefault", c.isDefault());
        v.put("apiKey", c.getApiKey() != null && !c.getApiKey().isBlank() ? "********" : "");
        return v;
    }
}
