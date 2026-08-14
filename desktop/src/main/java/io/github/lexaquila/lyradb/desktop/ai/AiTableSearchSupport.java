package io.github.lexaquila.lyradb.desktop.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库目录“智能找表”的纯元数据候选召回与结果解析。
 *
 * <p>该类只接收对象名、类型、所属库/Schema、注释和路径，不接收列样本或业务
 * 明细。先用本地文本相关度把更可能的候选排到前面，再把有字符上限的候选目录
 * 交给已配置的 AI 服务重排。</p>
 */
public final class AiTableSearchSupport {

    static final int DEFAULT_CONTEXT_CHARS = 90_000;
    private static final int MAX_RECOMMENDATIONS = 10;
    private static final Pattern JSON_FENCE = Pattern.compile(
            "(?is)```(?:json)?\\s*(\\{.*?}|\\[.*?])\\s*```");
    private static final Pattern TERM_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> SEARCH_FILLERS = Set.of(
            "帮我", "查找", "查询", "搜索", "推荐", "相关", "有关",
            "哪个", "哪些", "一张", "一些", "数据", "数据表", "表");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiTableSearchSupport() {
    }

    /**
     * 目录中的一个可打开对象。字段被刻意限制为目录元数据。
     */
    public record CatalogEntry(
            String name,
            String type,
            String namespace,
            String comment,
            String path) {

        public CatalogEntry {
            name = clean(name);
            type = clean(type);
            namespace = clean(namespace);
            comment = clean(comment);
            path = clean(path);
        }

        public String key() {
            if (!path.isBlank()) {
                return normalize(path);
            }
            return normalize(namespace + "/" + name);
        }

        String searchableText() {
            return normalize(String.join(" ",
                    name, type, namespace, comment, path));
        }
    }

    /**
     * 发送给 AI 前的候选快照。candidateId 是本地生成的短标识。
     */
    public record Candidate(
            String candidateId,
            CatalogEntry entry,
            int localScore) {
    }

    public record Prompt(
            String metadataContext,
            List<Candidate> candidates,
            int totalCount,
            boolean truncated) {

        public Prompt {
            metadataContext = Objects.requireNonNullElse(metadataContext, "");
            candidates = List.copyOf(candidates);
        }
    }

    public record Recommendation(CatalogEntry entry, String reason) {

        public Recommendation {
            Objects.requireNonNull(entry, "entry");
            reason = clean(reason);
            if (reason.isBlank()) {
                reason = "与输入的业务描述相关";
            }
        }
    }

    /**
     * 普通目录搜索：对象名、注释、所属库/Schema 和完整路径均参与匹配。
     */
    public static boolean matches(String query, CatalogEntry entry) {
        if (entry == null) {
            return false;
        }
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return true;
        }
        String text = entry.searchableText();
        if (text.contains(normalizedQuery)) {
            return true;
        }
        List<String> terms = terms(query);
        return !terms.isEmpty() && terms.stream().allMatch(text::contains);
    }

    /**
     * 生成有界的目录候选。相关度高的对象优先，零命中对象仍作为语义候选保留，
     * 使中文业务描述可以由模型与英文物理表名建立联系。
     */
    public static Prompt preparePrompt(
            String query, List<CatalogEntry> entries) {
        return preparePrompt(query, entries, DEFAULT_CONTEXT_CHARS);
    }

    static Prompt preparePrompt(
            String query, List<CatalogEntry> entries, int maxChars) {
        int safeMaxChars = Math.max(2_000, maxChars);
        List<ScoredEntry> ranked = rank(query, entries);
        StringBuilder context = new StringBuilder();
        context.append("安全边界：以下仅包含当前已加载的目录元数据，不包含业务明细或数据样本。\n")
                .append("用户输入：").append(sanitize(query)).append('\n')
                .append("字段：候选ID | 对象名 | 类型 | 所属库/Schema | 注释 | 完整路径\n");

        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean truncated = false;
        for (ScoredEntry scored : ranked) {
            CatalogEntry entry = scored.entry();
            if (!seen.add(entry.key())) {
                continue;
            }
            String candidateId = "T" + (candidates.size() + 1);
            String line = String.join(" | ",
                    candidateId,
                    sanitize(entry.name()),
                    sanitize(entry.type()),
                    sanitize(entry.namespace()),
                    sanitize(entry.comment()),
                    sanitize(entry.path())) + '\n';
            if (context.length() + line.length() > safeMaxChars) {
                truncated = true;
                break;
            }
            context.append(line);
            candidates.add(new Candidate(
                    candidateId, entry, scored.score()));
        }
        if (candidates.size() < ranked.size()) {
            truncated = true;
        }
        if (truncated) {
            context.append("[候选目录已按本地相关度排序并在本地安全截断]\n");
        }
        return new Prompt(context.toString(), candidates,
                ranked.size(), truncated);
    }

    /**
     * 本地降级推荐，只返回确实存在文本命中的目录对象。
     */
    public static List<Recommendation> localRecommendations(
            String query, List<CatalogEntry> entries, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Recommendation> result = new ArrayList<>();
        for (ScoredEntry scored : rank(query, entries)) {
            if (scored.score() <= 0 || result.size() >= safeLimit) {
                break;
            }
            result.add(new Recommendation(
                    scored.entry(), localReason(query, scored.entry())));
        }
        return List.copyOf(result);
    }

    /**
     * 解析模型返回的 JSON，并且只接受候选快照中真实存在的对象。
     */
    public static List<Recommendation> parseRecommendations(
            String response, Prompt prompt) {
        if (response == null || response.isBlank() || prompt == null) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(extractJson(response));
        } catch (Exception exception) {
            return List.of();
        }
        JsonNode array = root.isArray() ? root : root.path("recommendations");
        if (!array.isArray()) {
            return List.of();
        }

        Map<String, Candidate> byId = new LinkedHashMap<>();
        Map<String, Candidate> byPath = new LinkedHashMap<>();
        Map<String, List<Candidate>> byName = new LinkedHashMap<>();
        for (Candidate candidate : prompt.candidates()) {
            byId.put(normalize(candidate.candidateId()), candidate);
            byPath.put(candidate.entry().key(), candidate);
            byName.computeIfAbsent(normalize(candidate.entry().name()),
                    ignored -> new ArrayList<>()).add(candidate);
        }

        List<Recommendation> result = new ArrayList<>();
        Set<String> accepted = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject() || result.size() >= MAX_RECOMMENDATIONS) {
                continue;
            }
            Candidate candidate = findCandidate(item, byId, byPath, byName);
            if (candidate == null || !accepted.add(candidate.entry().key())) {
                continue;
            }
            String reason = firstText(item,
                    "reason", "理由", "matchReason", "recommendationReason");
            result.add(new Recommendation(candidate.entry(),
                    truncate(reason, 240)));
        }
        return List.copyOf(result);
    }

    public static String requestText(String query, Prompt prompt) {
        int candidateCount = prompt == null ? 0 : prompt.candidates().size();
        return "请根据用户输入，从下方 " + candidateCount
                + " 个候选目录对象中推荐最多 10 个最相关的表或视图。"
                + "只允许选择候选列表中的对象；每项给出可复核的简短理由。"
                + "用户输入：" + sanitize(query);
    }

    private static List<ScoredEntry> rank(
            String query, List<CatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ScoredEntry> result = new ArrayList<>();
        int order = 0;
        Set<String> seen = new HashSet<>();
        for (CatalogEntry entry : entries) {
            if (entry == null || entry.name().isBlank()
                    || !seen.add(entry.key())) {
                continue;
            }
            result.add(new ScoredEntry(
                    entry, score(query, entry), order++));
        }
        result.sort(Comparator
                .comparingInt(ScoredEntry::score).reversed()
                .thenComparingInt(ScoredEntry::order));
        return result;
    }

    private static int score(String query, CatalogEntry entry) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return 0;
        }
        String name = normalize(entry.name());
        String comment = normalize(entry.comment());
        String namespace = normalize(entry.namespace());
        String path = normalize(entry.path());
        int score = 0;
        if (name.equals(normalizedQuery)) {
            score += 1_000;
        } else if (name.contains(normalizedQuery)) {
            score += 420;
        }
        if (comment.contains(normalizedQuery)) {
            score += 360;
        }
        if (namespace.contains(normalizedQuery)) {
            score += 180;
        }
        if (path.contains(normalizedQuery)) {
            score += 150;
        }
        for (String term : terms(query)) {
            if (name.contains(term)) {
                score += 90;
            }
            if (comment.contains(term)) {
                score += 80;
            }
            if (namespace.contains(term)) {
                score += 36;
            }
            if (path.contains(term)) {
                score += 28;
            }
        }
        return score;
    }

    private static String localReason(String query, CatalogEntry entry) {
        String normalizedQuery = normalize(query);
        List<String> queryTerms = terms(query);
        if (contains(entry.comment(), normalizedQuery, queryTerms)) {
            return "本地降级：注释与输入匹配";
        }
        if (contains(entry.name(), normalizedQuery, queryTerms)) {
            return "本地降级：对象名与输入匹配";
        }
        if (contains(entry.namespace(), normalizedQuery, queryTerms)) {
            return "本地降级：所属库 / Schema 与输入匹配";
        }
        return "本地降级：完整路径与输入匹配";
    }

    private static boolean contains(
            String value, String query, List<String> queryTerms) {
        String normalized = normalize(value);
        return (!query.isBlank() && normalized.contains(query))
                || queryTerms.stream().anyMatch(normalized::contains);
    }

    private static Candidate findCandidate(
            JsonNode item,
            Map<String, Candidate> byId,
            Map<String, Candidate> byPath,
            Map<String, List<Candidate>> byName) {
        String id = firstText(item,
                "candidateId", "candidate_id", "id", "候选ID");
        Candidate candidate = byId.get(normalize(id));
        if (candidate != null) {
            return candidate;
        }
        String path = firstText(item,
                "path", "fullPath", "full_path", "完整路径");
        candidate = byPath.get(normalize(path));
        if (candidate != null) {
            return candidate;
        }
        String name = firstText(item,
                "name", "objectName", "object_name", "对象名");
        List<Candidate> named = byName.get(normalize(name));
        return named != null && named.size() == 1 ? named.get(0) : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()
                    && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static String extractJson(String response) {
        String trimmed = response.trim();
        Matcher matcher = JSON_FENCE.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return trimmed.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }
        return trimmed;
    }

    private static List<String> terms(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2 && !SEARCH_FILLERS.contains(term)) {
                terms.add(term);
            }
            addHanBigrams(terms, term);
            for (String part : term.split("_+")) {
                if (part.length() >= 2 && !SEARCH_FILLERS.contains(part)) {
                    terms.add(part);
                }
                addHanBigrams(terms, part);
            }
        }
        return List.copyOf(terms);
    }

    private static void addHanBigrams(
            Set<String> terms, String value) {
        int[] codePoints = value.codePoints().toArray();
        boolean containsHan = java.util.Arrays.stream(codePoints)
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN);
        if (!containsHan || codePoints.length < 2) {
            return;
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            String bigram = new String(codePoints, index, 2);
            if (!SEARCH_FILLERS.contains(bigram)) {
                terms.add(bigram);
            }
        }
    }

    private static String sanitize(String value) {
        return clean(value).replace('\r', ' ')
                .replace('\n', ' ').replace('\t', ' ')
                .replace('|', '｜');
    }

    private static String normalize(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxLength) {
        String cleaned = clean(value).replaceAll("\\s+", " ");
        return cleaned.length() <= maxLength
                ? cleaned : cleaned.substring(0, maxLength) + "…";
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private record ScoredEntry(
            CatalogEntry entry, int score, int order) {
    }
}
