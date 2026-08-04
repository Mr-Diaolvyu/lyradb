package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.repository.AiProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Provider 服务与 OpenAI-compatible 客户端。
 *
 * <p>Provider 严格绑定工作空间；API Key 使用认证加密。出站地址受服务端
 * 白名单约束；私有部署需显式开启并使用精确主机白名单。客户端具有连接、
 * 读取超时和响应体硬上限。</p>
 */
@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_MESSAGES = 100;

    private static final Map<String, Map<String, String>> PRESETS = new LinkedHashMap<>() {{
        put("bailian", Map.of("displayName", "阿里云百炼",
                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "model", "qwen-plus"));
        put("glm", Map.of("displayName", "智谱GLM",
                "baseUrl", "https://open.bigmodel.cn/api/paas/v4", "model", "glm-4-flash"));
        put("doubao", Map.of("displayName", "火山豆包",
                "baseUrl", "https://ark.cn-beijing.volces.com/api/v3", "model", "doubao-pro-32k"));
        put("deepseek", Map.of("displayName", "DeepSeek",
                "baseUrl", "https://api.deepseek.com/v1", "model", "deepseek-chat"));
        put("gpt", Map.of("displayName", "OpenAI GPT",
                "baseUrl", "https://api.openai.com/v1", "model", "gpt-4o-mini"));
        put("custom", Map.of("displayName", "自定义", "baseUrl", "", "model", ""));
    }};

    private final AiProviderConfigRepository repository;
    private final CredentialService credentialService;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final AiOperationalMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public AiProviderService(
            AiProviderConfigRepository repository,
            CredentialService credentialService,
            OutboundUrlPolicy outboundUrlPolicy,
            AiOperationalMetrics metrics) {
        this.repository = repository;
        this.credentialService = credentialService;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.metrics = metrics;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection,
                    String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(requestFactory);
        // 避免默认错误处理器先无界读取错误响应；状态码由有界 extractor 检查。
        this.restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
    }

    public Map<String, Map<String, String>> presets() {
        return PRESETS;
    }

    public List<Map<String, Object>> listMasked(String workspaceId) {
        requireWorkspaceId(workspaceId);
        List<AiProviderConfig> all =
                repository.findByWorkspaceIdOrderByIsDefaultDescCreatedAtDesc(workspaceId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiProviderConfig config : all) {
            result.add(toMaskedView(config));
        }
        return result;
    }

    public AiProviderConfig create(String workspaceId, String providerKey, String displayName,
            String baseUrl, String apiKey, String model, Double temperature,
            Integer maxTokens, boolean isDefault, String deploymentMode) {
        requireWorkspaceId(workspaceId);
        String mode = normalizeDeploymentMode(deploymentMode);
        validateConfiguration(displayName, baseUrl, apiKey, model,
                temperature, maxTokens, mode);

        AiProviderConfig config = new AiProviderConfig();
        config.setWorkspaceId(workspaceId);
        config.setProviderKey(providerKey);
        config.setDisplayName(displayName.trim());
        config.setBaseUrl(normalizeBaseUrl(baseUrl, mode));
        config.setDeploymentMode(mode);
        config.setModel(model.trim());
        config.setTemperature(temperature != null ? temperature : 0.2);
        config.setMaxTokens(maxTokens != null ? maxTokens : 2048);
        config.setEnabled(true);
        config.setDefault(isDefault);
        config.setApiKey(credentialService.encryptValue(
                apiKey == null ? "" : apiKey));

        if (isDefault) {
            repository.findByWorkspaceIdOrderByIsDefaultDescCreatedAtDesc(workspaceId)
                    .stream().filter(AiProviderConfig::isDefault).forEach(existing -> {
                        existing.setDefault(false);
                        repository.save(existing);
                    });
        }
        return repository.save(config);
    }

    public void delete(String id, String workspaceId) {
        AiProviderConfig config = repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Provider 不存在或不属于当前工作空间"));
        repository.delete(config);
    }

    public void setDefault(String id, String workspaceId) {
        AiProviderConfig target = repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Provider 不存在或不属于当前工作空间"));
        for (AiProviderConfig config :
                repository.findByWorkspaceIdOrderByIsDefaultDescCreatedAtDesc(workspaceId)) {
            config.setDefault(config.getId().equals(id));
            repository.save(config);
        }
        target.setDefault(true);
        repository.save(target);
    }

    public AiProviderConfig resolveDefault(String workspaceId) {
        requireWorkspaceId(workspaceId);
        AiProviderConfig config = repository
                .findByWorkspaceIdAndIsDefaultTrueAndEnabledTrue(workspaceId)
                .orElseThrow(() -> new IllegalStateException(
                        "当前工作空间未配置默认 AI Provider"));
        String migrated = credentialService.encryptValue(config.getApiKey());
        if (!migrated.equals(config.getApiKey())) {
            config.setApiKey(migrated);
            config = repository.save(config);
        }
        return config;
    }

    public String chat(AiProviderConfig config, List<Map<String, String>> messages) {
        return chatWithUsage(config, messages).content();
    }

    public AiProviderChatResult chatWithUsage(
            AiProviderConfig config, List<Map<String, String>> messages) {
        long started = System.currentTimeMillis();
        boolean success = false;
        validateMessages(messages);
        URI endpoint = chatEndpoint(config);
        String key = credentialService.decryptValue(config.getApiKey());
        try {
            Map<String, Object> body = requestBody(config, messages, false);
            byte[] requestJson = serializeBounded(body);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.execute(endpoint, HttpMethod.POST,
                    request -> writeRequest(request.getHeaders(), request.getBody(), key, requestJson),
                    clientResponse -> {
                        int status = clientResponse.getStatusCode().value();
                        try (InputStream input = new BoundedInputStream(
                                clientResponse.getBody(), MAX_RESPONSE_BYTES)) {
                            if (status < 200 || status >= 300) {
                                input.transferTo(java.io.OutputStream.nullOutputStream());
                                throw new IllegalStateException("AI 服务返回非成功状态");
                            }
                            return objectMapper.readValue(input,
                                    new TypeReference<Map<String, Object>>() { });
                        }
                    });
            success = true;
            return extractChatResult(response);
        } catch (Exception e) {
            log.error("AI 调用失败: provider={}, type={}",
                    config.getId(), e.getClass().getSimpleName(), e);
            throw new IllegalStateException("AI 调用失败，请稍后重试", e);
        } finally {
            metrics.record(AiOperationalMetrics.Operation.PROVIDER_CHAT,
                    success, System.currentTimeMillis() - started);
        }
    }

    /**
     * 调用 OpenAI-compatible 工具协议。只解析模型提议，不在客户端层执行工具。
     */
    public AiProviderToolTurn chatWithTools(
            AiProviderConfig config,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        long started = System.currentTimeMillis();
        boolean success = false;
        validateToolMessages(messages, tools);
        URI endpoint = chatEndpoint(config);
        String key = credentialService.decryptValue(config.getApiKey());
        try {
            byte[] requestJson = serializeBounded(
                    requestToolBody(config, messages, tools));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.execute(
                    endpoint, HttpMethod.POST,
                    request -> writeRequest(request.getHeaders(),
                            request.getBody(), key, requestJson),
                    clientResponse -> {
                        int status = clientResponse.getStatusCode().value();
                        try (InputStream input = new BoundedInputStream(
                                clientResponse.getBody(), MAX_RESPONSE_BYTES)) {
                            if (status < 200 || status >= 300) {
                                input.transferTo(
                                        java.io.OutputStream.nullOutputStream());
                                throw new IllegalStateException(
                                        "AI 工具服务返回非成功状态");
                            }
                            return objectMapper.readValue(input,
                                    new TypeReference<Map<String, Object>>() { });
                        }
                    });
            success = true;
            return extractToolTurn(response);
        } catch (Exception exception) {
            log.error("AI 工具调用失败: provider={}, type={}",
                    config.getId(), exception.getClass().getSimpleName(),
                    exception);
            throw new IllegalStateException("AI 工具调用失败，请稍后重试", exception);
        } finally {
            metrics.record(AiOperationalMetrics.Operation.PROVIDER_TOOL_CHAT,
                    success, System.currentTimeMillis() - started);
        }
    }

    /** 调用 OpenAI-compatible embeddings；调用方决定是否降级。 */
    public List<List<Double>> embed(
            AiProviderConfig config, String embeddingModel,
            List<String> inputs) {
        long started = System.currentTimeMillis();
        boolean success = false;
        if (embeddingModel == null || embeddingModel.isBlank()
                || embeddingModel.length() > 200) {
            throw new IllegalArgumentException("Embedding 模型必填且最长 200 字符");
        }
        if (inputs == null || inputs.isEmpty() || inputs.size() > 64
                || inputs.stream().anyMatch(value -> value == null
                        || value.isBlank() || value.length() > 20_000)) {
            throw new IllegalArgumentException(
                    "Embedding 输入数量必须在 1-64 之间，单项最长 20000 字符");
        }
        URI endpoint = embeddingEndpoint(config);
        String key = credentialService.decryptValue(config.getApiKey());
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", embeddingModel.trim());
            body.put("input", inputs);
            body.put("encoding_format", "float");
            byte[] requestJson = serializeBounded(body);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.execute(
                    endpoint, HttpMethod.POST,
                    request -> writeRequest(request.getHeaders(),
                            request.getBody(), key, requestJson),
                    clientResponse -> {
                        int status = clientResponse.getStatusCode().value();
                        try (InputStream input = new BoundedInputStream(
                                clientResponse.getBody(), MAX_RESPONSE_BYTES)) {
                            if (status < 200 || status >= 300) {
                                input.transferTo(
                                        java.io.OutputStream.nullOutputStream());
                                throw new IllegalStateException(
                                        "Embedding 服务返回非成功状态");
                            }
                            return objectMapper.readValue(input,
                                    new TypeReference<Map<String, Object>>() { });
                        }
                    });
            success = true;
            return extractEmbeddings(response, inputs.size());
        } catch (Exception exception) {
            log.warn("Embedding 调用失败: provider={}, type={}",
                    config.getId(), exception.getClass().getSimpleName());
            throw new IllegalStateException("Embedding 调用失败", exception);
        } finally {
            metrics.record(AiOperationalMetrics.Operation.PROVIDER_EMBED,
                    success, System.currentTimeMillis() - started);
        }
    }

    public void streamChat(AiProviderConfig config, List<Map<String, String>> messages,
            java.util.function.Consumer<String> onDelta, Runnable onDone,
            java.util.function.Consumer<Exception> onError) {
        long started = System.currentTimeMillis();
        boolean success = false;
        try {
            validateMessages(messages);
            URI endpoint = chatEndpoint(config);
            String key = credentialService.decryptValue(config.getApiKey());
            byte[] requestJson = serializeBounded(requestBody(config, messages, true));

            restTemplate.execute(endpoint, HttpMethod.POST,
                    request -> writeRequest(request.getHeaders(), request.getBody(), key, requestJson),
                    response -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) {
                            try (InputStream input = new BoundedInputStream(
                                    response.getBody(), MAX_RESPONSE_BYTES)) {
                                input.transferTo(java.io.OutputStream.nullOutputStream());
                            }
                            throw new IllegalStateException("AI 服务返回非成功状态");
                        }
                        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                                new BoundedInputStream(response.getBody(), MAX_STREAM_BYTES),
                                StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String data = line.substring(5).trim();
                                if (data.isEmpty() || "[DONE]".equals(data)) {
                                    if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                    continue;
                                }
                                parseDelta(data, onDelta);
                            }
                        }
                        onDone.run();
                        return null;
                    });
            success = true;
        } catch (Exception e) {
            log.error("AI 流式调用失败: provider={}, type={}",
                    config.getId(), e.getClass().getSimpleName(), e);
            onError.accept(new IllegalStateException("AI 流式调用失败，请稍后重试"));
        } finally {
            metrics.record(AiOperationalMetrics.Operation.PROVIDER_STREAM,
                    success, System.currentTimeMillis() - started);
        }
    }

    private URI chatEndpoint(AiProviderConfig config) {
        String mode = normalizeDeploymentMode(config.getDeploymentMode());
        String base = normalizeBaseUrl(config.getBaseUrl(), mode);
        // 配置创建后 DNS 可能变化，调用前必须重新校验。
        outboundUrlPolicy.validateAi(base, mode);
        return outboundUrlPolicy.validateAi(
                base + "/chat/completions", mode);
    }

    private URI embeddingEndpoint(AiProviderConfig config) {
        String mode = normalizeDeploymentMode(config.getDeploymentMode());
        String base = normalizeBaseUrl(config.getBaseUrl(), mode);
        // 与 chat 一样在每次调用前重验 DNS，避免配置后的 DNS rebinding。
        outboundUrlPolicy.validateAi(base, mode);
        return outboundUrlPolicy.validateAi(base + "/embeddings", mode);
    }

    private String normalizeBaseUrl(String baseUrl, String deploymentMode) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        URI validated = outboundUrlPolicy.validateAi(
                normalized, deploymentMode);
        if (validated.getQuery() != null) {
            throw new IllegalArgumentException("AI Provider baseUrl 不允许查询参数");
        }
        return normalized;
    }

    private void validateConfiguration(String displayName, String baseUrl, String apiKey,
            String model, Double temperature, Integer maxTokens,
            String deploymentMode) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 100) {
            throw new IllegalArgumentException("displayName 必填且最长 100 字符");
        }
        if (apiKey != null && apiKey.length() > 300) {
            throw new IllegalArgumentException("apiKey 最长 300 字符");
        }
        if ("PUBLIC".equals(deploymentMode)
                && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException(
                    "公共 Provider 的 apiKey 必填");
        }
        if (model == null || model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("model 必填且最长 200 字符");
        }
        double actualTemperature = temperature != null ? temperature : 0.2;
        int actualMaxTokens = maxTokens != null ? maxTokens : 2048;
        if (actualTemperature < 0 || actualTemperature > 2) {
            throw new IllegalArgumentException("temperature 必须在 0-2 之间");
        }
        if (actualMaxTokens < 1 || actualMaxTokens > 131_072) {
            throw new IllegalArgumentException("maxTokens 必须在 1-131072 之间");
        }
        normalizeBaseUrl(baseUrl, deploymentMode);
    }

    private static String normalizeDeploymentMode(String value) {
        String mode = value == null || value.isBlank()
                ? "PUBLIC"
                : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PUBLIC".equals(mode) && !"PRIVATE".equals(mode)) {
            throw new IllegalArgumentException(
                    "deploymentMode 仅支持 PUBLIC 或 PRIVATE");
        }
        return mode;
    }

    private void validateMessages(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("消息数量必须在 1-" + MAX_MESSAGES + " 之间");
        }
    }

    private void validateToolMessages(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        if (messages == null || messages.isEmpty()
                || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "工具消息数量必须在 1-" + MAX_MESSAGES + " 之间");
        }
        if (tools == null || tools.isEmpty() || tools.size() > 16) {
            throw new IllegalArgumentException("工具数量必须在 1-16 之间");
        }
        if (messages.stream().anyMatch(message -> message == null
                || !(message.get("role") instanceof String role)
                || role.isBlank())) {
            throw new IllegalArgumentException("每条工具消息都必须包含角色");
        }
    }

    private Map<String, Object> requestBody(AiProviderConfig config,
            List<Map<String, String>> messages, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.2);
        if (config.getMaxTokens() != null) {
            body.put("max_tokens", config.getMaxTokens());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private Map<String, Object> requestToolBody(
            AiProviderConfig config,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        body.put("temperature", config.getTemperature() == null
                ? 0.2 : config.getTemperature());
        if (config.getMaxTokens() != null) {
            body.put("max_tokens", config.getMaxTokens());
        }
        return body;
    }

    private byte[] serializeBounded(Map<String, Object> body) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(body);
        if (json.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("AI 请求内容不得超过 1 MiB");
        }
        return json;
    }

    private void writeRequest(HttpHeaders headers, java.io.OutputStream output,
            String key, byte[] requestJson) throws IOException {
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null && !key.isBlank()) {
            headers.setBearerAuth(key);
        }
        output.write(requestJson);
    }

    private AiProviderChatResult extractChatResult(
            Map<String, Object> response) {
        if (response != null && response.get("choices") instanceof List<?> choices
                && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") != null) {
            return new AiProviderChatResult(message.get("content").toString(),
                    usage(response.get("usage")));
        }
        throw new IllegalStateException("AI 返回格式异常");
    }

    private AiProviderToolTurn extractToolTurn(
            Map<String, Object> response) {
        if (response == null
                || !(response.get("choices") instanceof List<?> choices)
                || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("AI 工具响应格式异常");
        }
        Object rawContent = message.get("content");
        String content = rawContent == null ? "" : rawContent.toString();
        List<AiProviderToolTurn.ToolCall> calls = new ArrayList<>();
        Object rawCalls = message.get("tool_calls");
        if (rawCalls instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> call)
                        || !(call.get("function") instanceof Map<?, ?> function)) {
                    throw new IllegalStateException("AI 工具调用格式异常");
                }
                Object id = call.get("id");
                Object name = function.get("name");
                Object arguments = function.get("arguments");
                calls.add(new AiProviderToolTurn.ToolCall(
                        id == null ? null : id.toString(),
                        name == null ? null : name.toString(),
                        arguments == null ? "{}" : arguments.toString()));
            }
        }
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        if (rawContent != null) {
            assistant.put("content", content);
        }
        if (!calls.isEmpty()) {
            assistant.put("tool_calls", rawCalls);
        }
        AiProviderToolTurn.Usage usage = usage(response.get("usage"));
        return new AiProviderToolTurn(content, calls, usage, assistant);
    }

    static List<List<Double>> extractEmbeddings(
            Map<String, Object> response, int expectedCount) {
        if (response == null
                || !(response.get("data") instanceof List<?> data)
                || data.size() != expectedCount) {
            throw new IllegalStateException("Embedding 返回数量异常");
        }
        List<IndexedEmbedding> indexed = new ArrayList<>();
        for (int position = 0; position < data.size(); position++) {
            Object item = data.get(position);
            if (!(item instanceof Map<?, ?> values)
                    || !(values.get("embedding") instanceof List<?> raw)) {
                throw new IllegalStateException("Embedding 返回格式异常");
            }
            int index = values.get("index") instanceof Number number
                    ? number.intValue() : position;
            if (index < 0 || index >= expectedCount
                    || raw.isEmpty() || raw.size() > 8_192) {
                throw new IllegalStateException("Embedding 维度或索引异常");
            }
            List<Double> vector = new ArrayList<>(raw.size());
            for (Object coordinate : raw) {
                if (!(coordinate instanceof Number number)) {
                    throw new IllegalStateException("Embedding 坐标不是数字");
                }
                double value = number.doubleValue();
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException("Embedding 坐标不是有限数值");
                }
                vector.add(value);
            }
            indexed.add(new IndexedEmbedding(index, List.copyOf(vector)));
        }
        indexed.sort(java.util.Comparator.comparingInt(
                IndexedEmbedding::index));
        for (int index = 0; index < indexed.size(); index++) {
            if (indexed.get(index).index() != index) {
                throw new IllegalStateException("Embedding 索引不连续");
            }
        }
        int dimension = indexed.get(0).vector().size();
        if (indexed.stream().anyMatch(value ->
                value.vector().size() != dimension)) {
            throw new IllegalStateException("Embedding 维度不一致");
        }
        return indexed.stream().map(IndexedEmbedding::vector).toList();
    }

    private record IndexedEmbedding(int index, List<Double> vector) {
    }

    private static AiProviderToolTurn.Usage usage(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            return new AiProviderToolTurn.Usage(0, 0, 0);
        }
        long prompt = nonNegativeLong(values.get("prompt_tokens"));
        long completion = nonNegativeLong(values.get("completion_tokens"));
        long total = nonNegativeLong(values.get("total_tokens"));
        if (total == 0) {
            total = prompt + completion;
        }
        return new AiProviderToolTurn.Usage(prompt, completion, total);
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.toString()));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private void parseDelta(String data, java.util.function.Consumer<String> onDelta) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(data, Map.class);
            if (frame.get("choices") instanceof List<?> choices && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> choice
                    && choice.get("delta") instanceof Map<?, ?> delta
                    && delta.get("content") != null) {
                onDelta.accept(delta.get("content").toString());
            }
        } catch (Exception e) {
            log.debug("忽略无法解析的 AI SSE 帧");
        }
    }

    private static void requireWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 必填");
        }
    }

    private Map<String, Object> toMaskedView(AiProviderConfig config) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", config.getId());
        view.put("workspaceId", config.getWorkspaceId());
        view.put("providerKey", config.getProviderKey());
        view.put("displayName", config.getDisplayName());
        view.put("baseUrl", config.getBaseUrl());
        view.put("deploymentMode", normalizeDeploymentMode(config.getDeploymentMode()));
        view.put("model", config.getModel());
        view.put("temperature", config.getTemperature());
        view.put("maxTokens", config.getMaxTokens());
        view.put("enabled", config.isEnabled());
        view.put("isDefault", config.isDefault());
        view.put("apiKey", config.getApiKey() != null && !config.getApiKey().isBlank()
                ? CredentialService.MASKED_VALUE : "");
        return view;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long readBytes;

        private BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int count) throws IOException {
            readBytes += count;
            if (readBytes > maxBytes) {
                throw new IOException("响应体超过安全上限");
            }
        }
    }

}
