package io.github.lexaquila.lyradb.desktop.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 个人版 OpenAI Chat Completions 兼容客户端。
 *
 * <p>API Key 只写入本次请求的 Authorization 头，不写日志、不上传 LyraDB
 * 配置文件。默认只允许 HTTPS；本机回环地址可使用 HTTP 连接本地模型。</p>
 */
public final class OpenAiCompatibleClient {

    private static final int MAX_CONTEXT_CHARS = 120_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), new ObjectMapper());
    }

    OpenAiCompatibleClient(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public String complete(AiProfile profile, AiTask task, String request,
            String dbType, String schemaContext, String currentSql)
            throws IOException, InterruptedException {
        validateProfile(profile);
        if (task == null) {
            throw new IllegalArgumentException("AI 任务不能为空");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", profile.getModel());
        payload.put("temperature", profile.getTemperature());
        payload.put("max_tokens", profile.getMaxTokens());
        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt(task, dbType));
        messages.addObject()
                .put("role", "user")
                .put("content", userPrompt(request, schemaContext, currentSql));

        HttpRequest.Builder builder = HttpRequest.newBuilder(chatEndpoint(profile.getBaseUrl()))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (!profile.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + profile.getApiKey());
        }

        HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("AI 服务响应超过 2 MiB 安全上限");
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI 服务返回 HTTP " + response.statusCode()
                    + ": " + safeError(body));
        }
        JsonNode root = mapper.readTree(body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("AI 服务响应中没有可用内容");
        }
        return content.asText();
    }

    public String test(AiProfile profile) throws IOException, InterruptedException {
        return complete(profile, AiTask.EXPLAIN,
                "只回复“连接成功”四个字。", "SQL", "", "SELECT 1;");
    }

    public static void validateProfile(AiProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("AI 配置不能为空");
        }
        chatEndpoint(profile.getBaseUrl());
        if (profile.getModel().isBlank()) {
            throw new IllegalArgumentException("模型不能为空");
        }
        AiProviderPreset preset = AiProviderPreset.find(profile.getProviderKey());
        if (profile.getApiKey().isBlank() && !preset.apiKeyOptional()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
    }

    public static URI chatEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("AI Base URL 不能为空");
        }
        final URI base;
        try {
            base = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AI Base URL 格式无效", exception);
        }
        String scheme = lower(base.getScheme());
        String host = lower(base.getHost());
        if (host.isBlank() || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("AI Base URL 不允许凭据、查询参数或片段");
        }
        boolean loopback = host.equals("localhost")
                || host.equals("127.0.0.1") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException(
                    "AI 服务必须使用 HTTPS；仅本机回环地址可使用 HTTP");
        }
        String path = base.getPath() == null ? "" : base.getPath();
        if (path.endsWith("/chat/completions")) {
            return base;
        }
        String normalized = path.endsWith("/")
                ? path + "chat/completions" : path + "/chat/completions";
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                    normalized, null, null);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法构造 AI 请求地址", exception);
        }
    }

    private static String systemPrompt(AiTask task, String dbType) {
        String normalizedType = dbType == null
                ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        String outputRule = switch (normalizedType) {
            case "REDIS" -> "仅输出 LyraDB 支持的单条 Redis 命令，并放在 "
                    + "```redis 代码块；支持 GET/KEYS/SCAN/TYPE/HGETALL/LRANGE/"
                    + "SMEMBERS/ZRANGE/STRLEN/DBSIZE/INFO/TTL/SET/DEL/EXPIRE/PERSIST。";
            case "MONGODB" -> "MongoDB 读取使用 db.collection；写入使用包含 "
                    + "op、db、collection、filter/document/update 的 JSON DSL，"
                    + "并放在 ```mongodb 代码块。";
            default -> "优先输出适配当前数据库方言的 SQL，并放在 ```sql 代码块。";
        };
        return """
                你是 LyraDB 的数据库工程助手。当前数据库类型：%s。
                任务：%s。
                必须遵守：
                1. 不得声称已执行 SQL；你没有数据库执行权限。
                2. 缺少表结构或业务口径时明确列出假设，不得仅凭字段名编造业务定义。
                3. 涉及 UPDATE、DELETE、DROP、TRUNCATE、ALTER 时突出不可逆风险和回滚建议。
                4. %s
                5. 使用简体中文，回答紧凑、可复核。
                """.formatted(dbType == null || dbType.isBlank() ? "未知" : dbType,
                task.instruction(), outputRule);
    }

    private static String userPrompt(String request, String schemaContext,
            String currentSql) {
        String text = """
                用户要求：
                %s

                当前 SQL：
                %s

                已知结构上下文：
                %s
                """.formatted(blankAsNone(request), blankAsNone(currentSql),
                blankAsNone(schemaContext));
        if (text.length() > MAX_CONTEXT_CHARS) {
            return text.substring(0, MAX_CONTEXT_CHARS)
                    + "\n[上下文已在本地截断]";
        }
        return text;
    }

    private static String safeError(String body) {
        if (body == null || body.isBlank()) {
            return "无响应正文";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(500, compact.length()));
    }

    private static String blankAsNone(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value.trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
