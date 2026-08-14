package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.EnterpriseTableSearchResponse;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对授权元数据目录执行两阶段智能找表。
 *
 * <p>第一阶段完全在服务端本地按表名、Schema、完整路径和中文注释召回候选；
 * 第二阶段只把这些轻量元数据发送给当前工作空间配置的模型进行重排。不会读取或
 * 发送业务数据行，模型返回的对象也必须再次命中授权候选集合。</p>
 */
@Service
public class EnterpriseTableSearchService {

    static final int DEFAULT_LIMIT = 12;
    static final int MAX_LIMIT = 30;
    static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_AI_CANDIDATES = 160;
    private static final int MAX_REMARK_CHARS = 300;

    private final EnterpriseMetadataCatalogService catalogService;
    private final AiProviderService aiProviderService;
    private final AiFeatureGate featureGate;
    private final ObjectMapper objectMapper;

    public EnterpriseTableSearchService(
            EnterpriseMetadataCatalogService catalogService,
            AiProviderService aiProviderService,
            AiFeatureGate featureGate,
            ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.aiProviderService = aiProviderService;
        this.featureGate = featureGate;
        this.objectMapper = objectMapper;
    }

    public EnterpriseTableSearchResponse search(
            String workspaceId, String grantedSourceName,
            String query, Integer requestedLimit) throws Exception {
        featureGate.requireEnabled(AiFeature.ASK_LYRA);
        String intent = required(query, "query", MAX_QUERY_CHARS);
        String source = required(
                grantedSourceName, "grantedSourceName", 100);
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        int limit = requestedLimit == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));

        EnterpriseMetadataCatalog catalog =
                catalogService.catalog(source, false);
        List<ScoredTable> ranked = rankLocally(
                catalog.getTables(), intent);
        List<ScoredTable> candidates = aiCandidates(ranked);
        if (candidates.isEmpty()) {
            return new EnterpriseTableSearchResponse(
                    "LOCAL_FALLBACK",
                    "当前授权目录中没有可推荐的表或视图",
                    List.of());
        }

        try {
            AiProviderConfig provider =
                    aiProviderService.resolveDefault(workspaceId);
            String reply = aiProviderService.chat(
                    provider, aiMessages(intent, candidates, limit));
            List<EnterpriseTableSearchResponse.Recommendation> recommendations =
                    parseRecommendations(reply, candidates, limit);
            if (!recommendations.isEmpty()) {
                return new EnterpriseTableSearchResponse(
                        "AI",
                        "AI 已根据当前授权元数据完成推荐；未读取业务数据行",
                        recommendations);
            }
        } catch (RuntimeException ignored) {
            // 模型未配置、请求失败或输出不合规时，保留可用的本地搜索结果。
        }
        return localFallback(ranked, limit,
                "AI 暂不可用，已按表名、Schema、完整路径和中文注释返回本地结果");
    }

    static List<ScoredTable> rankLocally(
            List<EnterpriseMetadataCatalog.Table> tables, String query) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        Set<String> terms = searchTerms(normalizedQuery);
        List<ScoredTable> values = new ArrayList<>();
        for (EnterpriseMetadataCatalog.Table table : tables) {
            if (table == null || table.getQualifiedName() == null
                    || table.getQualifiedName().isBlank()) {
                continue;
            }
            String name = normalize(table.getName());
            String schema = normalize(table.getSchema());
            String path = normalize(table.getQualifiedName());
            String remarks = normalize(table.getRemarks());
            int score = containsScore(name, normalizedQuery, 140)
                    + containsScore(remarks, normalizedQuery, 170)
                    + containsScore(path, normalizedQuery, 100)
                    + containsScore(schema, normalizedQuery, 70);
            for (String term : terms) {
                score += containsScore(name, term, 28)
                        + containsScore(remarks, term, 36)
                        + containsScore(path, term, 18)
                        + containsScore(schema, term, 12);
            }
            values.add(new ScoredTable(table, score,
                    localReason(table, normalizedQuery, terms)));
        }
        values.sort(Comparator.comparingInt(ScoredTable::score)
                .reversed()
                .thenComparing(value ->
                        value.table().getQualifiedName(),
                        String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(values);
    }

    private List<Map<String, String>> aiMessages(
            String query, List<ScoredTable> candidates, int limit) {
        List<Map<String, String>> payload = candidates.stream()
                .map(value -> {
                    EnterpriseMetadataCatalog.Table table = value.table();
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("path", clean(table.getQualifiedName()));
                    item.put("name", clean(table.getName()));
                    item.put("schema", clean(table.getSchema()));
                    item.put("type", clean(table.getType()));
                    item.put("comment", bounded(
                            table.getRemarks(), MAX_REMARK_CHARS));
                    return item;
                })
                .toList();
        final String candidateJson;
        try {
            candidateJson = objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化授权元数据候选", exception);
        }
        String system = """
                你是 LyraDB 的数据库元数据检索助手。只能从候选列表中推荐表或视图，
                不得编造路径、字段、业务口径或数据内容。根据用户意图、表名、Schema
                和注释排序。严格返回 JSON：
                {"recommendations":[{"path":"候选中的完整路径","reason":"简短中文理由","confidence":0}]}
                confidence 为 0 到 100 的整数，最多返回 %d 项，不要返回 SQL 或其他文字。
                """.formatted(limit);
        String user = "用户输入：" + query
                + "\n当前用户已授权的候选元数据（不含数据行）：\n"
                + candidateJson;
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user));
    }

    private List<EnterpriseTableSearchResponse.Recommendation>
            parseRecommendations(String reply, List<ScoredTable> candidates,
                    int limit) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        Map<String, ScoredTable> allowed = new LinkedHashMap<>();
        for (ScoredTable candidate : candidates) {
            allowed.put(normalize(candidate.table().getQualifiedName()), candidate);
        }
        try {
            String json = jsonPayload(reply);
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.isArray()
                    ? root : root.path("recommendations");
            if (!items.isArray()) {
                return List.of();
            }
            List<EnterpriseTableSearchResponse.Recommendation> results =
                    new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : items) {
                String path = clean(item.path("path").asText());
                ScoredTable candidate = allowed.get(normalize(path));
                if (candidate == null
                        || !seen.add(normalize(path))) {
                    continue;
                }
                String reason = bounded(
                        item.path("reason").asText(), 240);
                int confidence = Math.max(0,
                        Math.min(100,
                                item.path("confidence").asInt(70)));
                results.add(toRecommendation(candidate.table(),
                        reason.isBlank()
                                ? candidate.reason() : reason,
                        confidence));
                if (results.size() >= limit) {
                    break;
                }
            }
            return List.copyOf(results);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static EnterpriseTableSearchResponse localFallback(
            List<ScoredTable> ranked, int limit, String message) {
        List<ScoredTable> matched = ranked.stream()
                .filter(value -> value.score() > 0)
                .limit(limit)
                .toList();
        if (matched.isEmpty()) {
            matched = ranked.stream().limit(limit).toList();
        }
        List<EnterpriseTableSearchResponse.Recommendation> results =
                matched.stream()
                        .map(value -> toRecommendation(
                                value.table(), value.reason(),
                                localConfidence(value.score())))
                        .toList();
        return new EnterpriseTableSearchResponse(
                "LOCAL_FALLBACK", message, results);
    }

    private static List<ScoredTable> aiCandidates(
            List<ScoredTable> ranked) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ScoredTable> selected =
                new LinkedHashMap<>();
        ranked.stream()
                .filter(value -> value.score() > 0)
                .limit(MAX_AI_CANDIDATES)
                .forEach(value -> selected.put(
                        normalize(value.table().getQualifiedName()), value));
        ranked.stream()
                .filter(value -> value.table().getRemarks() != null
                        && !value.table().getRemarks().isBlank())
                .limit(MAX_AI_CANDIDATES)
                .forEach(value -> selected.putIfAbsent(
                        normalize(value.table().getQualifiedName()), value));
        ranked.stream()
                .limit(MAX_AI_CANDIDATES)
                .forEach(value -> selected.putIfAbsent(
                        normalize(value.table().getQualifiedName()), value));
        return selected.values().stream()
                .limit(MAX_AI_CANDIDATES)
                .toList();
    }

    private static EnterpriseTableSearchResponse.Recommendation
            toRecommendation(EnterpriseMetadataCatalog.Table table,
                    String reason, int confidence) {
        return new EnterpriseTableSearchResponse.Recommendation(
                clean(table.getQualifiedName()), clean(table.getName()),
                clean(table.getSchema()), clean(table.getType()),
                clean(table.getRemarks()), clean(reason), confidence);
    }

    private static String localReason(
            EnterpriseMetadataCatalog.Table table,
            String query, Set<String> terms) {
        String remarks = normalize(table.getRemarks());
        String name = normalize(table.getName());
        String schema = normalize(table.getSchema());
        String path = normalize(table.getQualifiedName());
        if (matches(remarks, query, terms)) {
            return "中文注释与输入内容匹配";
        }
        if (matches(name, query, terms)) {
            return "表名与输入内容匹配";
        }
        if (matches(schema, query, terms)) {
            return "Schema 与输入内容匹配";
        }
        if (matches(path, query, terms)) {
            return "完整路径与输入内容匹配";
        }
        return "当前授权目录中的候选对象";
    }

    private static boolean matches(
            String value, String query, Set<String> terms) {
        if (!query.isBlank() && value.contains(query)) {
            return true;
        }
        return terms.stream().anyMatch(value::contains);
    }

    private static Set<String> searchTerms(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : value.split(
                "[\\s\\p{Punct}，。；：、（）【】]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
            if (token.length() > 2) {
                for (int index = 0; index < token.length() - 1; index++) {
                    terms.add(token.substring(index, index + 2));
                }
            }
        }
        return terms;
    }

    private static int containsScore(
            String value, String token, int score) {
        return token == null || token.isBlank()
                || value == null || !value.contains(token) ? 0 : score;
    }

    private static int localConfidence(int score) {
        return score <= 0 ? 30 : Math.min(95, 50 + score / 8);
    }

    private static String jsonPayload(String value) {
        String text = value.trim();
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart
                : arrayStart < 0 ? objectStart
                : Math.min(objectStart, arrayStart);
        int objectEnd = text.lastIndexOf('}');
        int arrayEnd = text.lastIndexOf(']');
        int end = Math.max(objectEnd, arrayEnd);
        return start >= 0 && end >= start
                ? text.substring(start, end + 1) : text;
    }

    private static String required(
            String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 长度不得超过 " + maxLength);
        }
        return result;
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value, int maxLength) {
        String text = clean(value);
        return text.length() <= maxLength
                ? text : text.substring(0, maxLength);
    }

    record ScoredTable(
            EnterpriseMetadataCatalog.Table table,
            int score,
            String reason) {
    }
}
