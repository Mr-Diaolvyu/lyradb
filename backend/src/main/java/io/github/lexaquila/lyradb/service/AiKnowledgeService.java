package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;
import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.model.EvidenceRef;
import io.github.lexaquila.lyradb.ai.model.EvidenceTrustLevel;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeAssetView;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeDraftRequest;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeReviewRequest;
import io.github.lexaquila.lyradb.model.entity.AiKnowledgeAsset;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiKnowledgeAssetRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data Knowledge Core：草稿、审核、Verified Query 与仅已验证知识检索。
 */
@Service
public class AiKnowledgeService {

    private static final int MAX_ASSETS_PER_CONTEXT = 8;
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{IsHan}]{2,}|[A-Za-z0-9_]{2,}");

    private final AiKnowledgeAssetRepository repository;
    private final SecurityUtil securityUtil;
    private final AiFeatureGate featureGate;
    private final AuditService auditService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AiKnowledgeEmbeddingService embeddingService;

    public AiKnowledgeService(
            AiKnowledgeAssetRepository repository,
            SecurityUtil securityUtil,
            AiFeatureGate featureGate,
            AuditService auditService,
            AppProperties properties,
            ObjectMapper objectMapper,
            AiKnowledgeEmbeddingService embeddingService) {
        this.repository = repository;
        this.securityUtil = securityUtil;
        this.featureGate = featureGate;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
    }

    @Transactional(readOnly = true)
    public List<AiKnowledgeAssetView> listVerified(String workspaceId) {
        featureGate.requireEnabled(AiFeature.KNOWLEDGE_CORE);
        return repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(
                        requireWorkspace(workspaceId),
                        KnowledgeAssetStatus.VERIFIED,
                        PageRequest.of(0, 200))
                .stream().map(AiKnowledgeService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AiKnowledgeAssetView> listMine(String workspaceId) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        User user = securityUtil.requireCurrentUser();
        return repository.findByWorkspaceIdAndCreatedByOrderByUpdatedAtDesc(
                        requireWorkspace(workspaceId), user.getId(),
                        PageRequest.of(0, 500))
                .stream().map(AiKnowledgeService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AiKnowledgeAssetView> listForReview(String workspaceId) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        requireSteward();
        return repository.findByWorkspaceIdOrderByUpdatedAtDesc(
                        requireWorkspace(workspaceId), PageRequest.of(0, 500))
                .stream().map(AiKnowledgeService::view).toList();
    }

    @Transactional
    public AiKnowledgeAssetView createDraft(
            String workspaceId, AiKnowledgeDraftRequest request) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        if (request == null) {
            throw new IllegalArgumentException("知识草稿不能为空");
        }
        User user = securityUtil.requireCurrentUser();
        AiKnowledgeAsset asset = new AiKnowledgeAsset();
        asset.setWorkspaceId(requireWorkspace(workspaceId));
        asset.setType(requireType(request.getType()));
        asset.setStatus(KnowledgeAssetStatus.DRAFT);
        asset.setTitle(requireText(request.getTitle(), "标题", 200));
        asset.setDefinition(requireText(
                request.getDefinition(), "知识定义", 20_000));
        asset.setVerifiedSql(optionalText(
                request.getVerifiedSql(), 50_000));
        asset.setDbType(optionalText(request.getDbType(), 32));
        asset.setGrantedSourceName(optionalText(
                request.getGrantedSourceName(), 100));
        asset.setDefaultDatabase(optionalText(
                request.getDefaultDatabase(), 200));
        asset.setKeywords(joinKeywords(request.getKeywords()));
        asset.setSourceRef(optionalText(request.getSourceRef(), 1_000));
        asset.setCreatedBy(user.getId());
        validateTypeSpecific(asset);
        asset.setContentSha256(contentDigest(asset));
        AiKnowledgeAsset saved = repository.saveAndFlush(asset);
        auditService.recordCurrent(asset.getWorkspaceId(),
                "AI_KNOWLEDGE_DRAFT_CREATE", null,
                saved.getGrantedSourceName(), true, null);
        return view(saved);
    }

    @Transactional
    public AiKnowledgeAssetView submit(String workspaceId, String assetId) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        AiKnowledgeAsset asset = requireAsset(workspaceId, assetId);
        User user = securityUtil.requireCurrentUser();
        if (!user.getId().equals(asset.getCreatedBy()) && !canModerate()) {
            throw new AccessDeniedException("只能提交自己创建的知识草稿");
        }
        requireStatus(asset, KnowledgeAssetStatus.DRAFT);
        validateTypeSpecific(asset);
        asset.setStatus(KnowledgeAssetStatus.IN_REVIEW);
        asset.setAssetVersion(asset.getAssetVersion() + 1);
        AiKnowledgeAsset saved = repository.saveAndFlush(asset);
        auditService.recordCurrent(asset.getWorkspaceId(),
                "AI_KNOWLEDGE_SUBMIT", null,
                saved.getGrantedSourceName(), true, null);
        return view(saved);
    }

    @Transactional
    public AiKnowledgeAssetView review(
            String workspaceId, String assetId,
            AiKnowledgeReviewRequest request) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        requireSteward();
        if (request == null || request.getDecision() == null) {
            throw new IllegalArgumentException("审核决定不能为空");
        }
        AiKnowledgeAsset asset = requireAsset(workspaceId, assetId);
        User reviewer = securityUtil.requireCurrentUser();
        String decision = request.getDecision().trim()
                .toUpperCase(Locale.ROOT);
        switch (decision) {
            case "VERIFY" -> {
                requireStatus(asset, KnowledgeAssetStatus.IN_REVIEW);
                validateTypeSpecific(asset);
                asset.setStatus(KnowledgeAssetStatus.VERIFIED);
                refreshEmbedding(asset);
            }
            case "REJECT" -> {
                requireStatus(asset, KnowledgeAssetStatus.IN_REVIEW);
                requireText(request.getComment(), "驳回原因", 1_000);
                asset.setStatus(KnowledgeAssetStatus.REJECTED);
            }
            case "RETIRE" -> {
                requireStatus(asset, KnowledgeAssetStatus.VERIFIED);
                requireText(request.getComment(), "退役原因", 1_000);
                asset.setStatus(KnowledgeAssetStatus.RETIRED);
            }
            default -> throw new IllegalArgumentException(
                    "审核决定仅支持 VERIFY、REJECT 或 RETIRE");
        }
        asset.setReviewedBy(reviewer.getId());
        asset.setReviewComment(optionalText(request.getComment(), 1_000));
        asset.setReviewedAt(java.time.LocalDateTime.now());
        asset.setAssetVersion(asset.getAssetVersion() + 1);
        AiKnowledgeAsset saved = repository.saveAndFlush(asset);
        auditService.recordCurrent(asset.getWorkspaceId(),
                "AI_KNOWLEDGE_" + decision, null,
                saved.getGrantedSourceName(), true, null);
        return view(saved);
    }

    /**
     * 为 Ask Lyra 检索已验证知识。不会返回草稿、审核中、驳回或已退役资产。
     */
    @Transactional(readOnly = true)
    public KnowledgeContext retrieveVerified(
            String workspaceId, String grantedSourceName, String question) {
        featureGate.requireEnabled(AiFeature.KNOWLEDGE_CORE);
        String boundedQuestion = requireText(question, "问题", 20_000);
        String workspace = requireWorkspace(workspaceId);
        Optional<AiKnowledgeEmbeddingService.Embedding> queryEmbedding =
                embeddingService.embed(workspace, boundedQuestion);
        double lexicalWeight = properties.getAi().getKnowledgeLexicalWeight();
        List<ScoredAsset> scored = new ArrayList<>();
        for (AiKnowledgeAsset asset :
                repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(
                        workspace,
                        KnowledgeAssetStatus.VERIFIED,
                        PageRequest.of(0, 200))) {
            if (!matchesSource(asset, grantedSourceName)) {
                continue;
            }
            int lexicalScore = score(asset, boundedQuestion);
            double semanticScore = queryEmbedding
                    .map(value -> semanticScore(asset, value))
                    .orElse(0.0);
            double retrievalScore;
            if (queryEmbedding.isPresent()) {
                double normalizedLexical = Math.min(
                        1.0, lexicalScore / 20.0);
                retrievalScore = lexicalWeight * normalizedLexical
                        + (1.0 - lexicalWeight) * semanticScore;
            } else {
                retrievalScore = lexicalScore;
            }
            if (retrievalScore > 0.05) {
                scored.add(new ScoredAsset(asset, retrievalScore));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredAsset::score).reversed()
                .thenComparing(value -> value.asset().getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        int charLimit = properties.getAi().getMaxKnowledgeContextChars();
        List<Map<String, Object>> promptAssets = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        int usedChars = 0;
        for (ScoredAsset item : scored) {
            if (promptAssets.size() >= MAX_ASSETS_PER_CONTEXT) {
                break;
            }
            AiKnowledgeAsset asset = item.asset();
            Map<String, Object> promptAsset = promptAsset(asset);
            String serialized = serialize(promptAsset);
            if (usedChars + serialized.length() > charLimit) {
                continue;
            }
            usedChars += serialized.length();
            promptAssets.add(promptAsset);
            evidence.add(toEvidence(asset));
        }
        List<String> omitted = new ArrayList<>();
        if (evidence.size() < scored.size()) {
            omitted.add("knowledge-context-limit");
        }
        if (properties.getAi().isKnowledgeSemanticEnabled()
                && queryEmbedding.isEmpty()) {
            omitted.add("semantic-retrieval-unavailable-lexical-fallback");
        }
        String promptJson = serialize(promptAssets);
        return new KnowledgeContext(promptJson, evidence, omitted);
    }

    private AiKnowledgeAsset requireAsset(String workspaceId, String assetId) {
        String normalizedId = requireText(assetId, "知识资产 ID", 36);
        return repository.findByIdAndWorkspaceId(
                        normalizedId, requireWorkspace(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "知识资产不存在或不属于当前工作空间"));
    }

    private void requireSteward() {
        if (!canModerate()) {
            throw new AccessDeniedException(
                    "知识审核需要 STEWARD 或 DS_ADMIN 角色");
        }
    }

    private boolean canModerate() {
        return securityUtil.hasRole("STEWARD")
                || securityUtil.hasRole("DS_ADMIN");
    }

    private static void validateTypeSpecific(AiKnowledgeAsset asset) {
        if (asset.getType() == KnowledgeAssetType.VERIFIED_QUERY) {
            if (asset.getVerifiedSql() == null
                    || asset.getGrantedSourceName() == null) {
                throw new IllegalArgumentException(
                        "Verified Query 必须绑定逻辑数据源并提供只读 SQL");
            }
            SqlParseUtil.requireEnterpriseReadOnly(asset.getVerifiedSql());
        } else if (asset.getVerifiedSql() != null) {
            throw new IllegalArgumentException(
                    "只有 VERIFIED_QUERY 类型可以保存 SQL");
        }
    }

    private static boolean matchesSource(
            AiKnowledgeAsset asset, String grantedSourceName) {
        if (asset.getGrantedSourceName() == null) {
            return asset.getType() != KnowledgeAssetType.VERIFIED_QUERY;
        }
        return grantedSourceName != null
                && asset.getGrantedSourceName().equalsIgnoreCase(
                grantedSourceName.trim());
    }

    private static int score(AiKnowledgeAsset asset, String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        String title = asset.getTitle().toLowerCase(Locale.ROOT);
        int score = normalizedQuestion.contains(title)
                || title.contains(normalizedQuestion) ? 20 : 0;
        Set<String> questionTokens = tokens(normalizedQuestion);
        Set<String> assetTokens = tokens(title + " "
                + safe(asset.getKeywords()) + " "
                + bounded(asset.getDefinition(), 4_000));
        for (String token : questionTokens) {
            if (assetTokens.contains(token)) {
                score += token.codePointCount(0, token.length()) >= 3 ? 3 : 1;
            }
        }
        return score;
    }

    private double semanticScore(
            AiKnowledgeAsset asset,
            AiKnowledgeEmbeddingService.Embedding query) {
        if (asset.getEmbeddingModel() == null
                || !asset.getEmbeddingModel().equals(query.model())) {
            return 0;
        }
        return embeddingService.deserialize(asset.getEmbeddingJson())
                .map(vector -> AiKnowledgeEmbeddingService.cosine(
                        query.vector(), vector))
                .orElse(0.0);
    }

    private void refreshEmbedding(AiKnowledgeAsset asset) {
        embeddingService.embed(
                        asset.getWorkspaceId(), embeddingText(asset))
                .ifPresent(embedding -> {
                    asset.setEmbeddingModel(embedding.model());
                    asset.setEmbeddingJson(
                            embeddingService.serialize(embedding.vector()));
                });
    }

    private static String embeddingText(AiKnowledgeAsset asset) {
        return String.join("\n", asset.getType().name(), asset.getTitle(),
                asset.getDefinition(), safe(asset.getKeywords()),
                safe(asset.getVerifiedSql()), safe(asset.getSourceRef()));
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            result.add(token);
            if (token.codePoints().allMatch(
                    codePoint -> Character.UnicodeScript.of(codePoint)
                            == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> promptAsset(AiKnowledgeAsset asset) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", asset.getId());
        value.put("type", asset.getType());
        value.put("title", asset.getTitle());
        value.put("definition", bounded(asset.getDefinition(), 4_000));
        if (asset.getVerifiedSql() != null) {
            value.put("verifiedSql", asset.getVerifiedSql());
            value.put("dbType", asset.getDbType());
            value.put("grantedSourceName", asset.getGrantedSourceName());
            value.put("defaultDatabase", asset.getDefaultDatabase());
        }
        if (asset.getIngestionSource() != null) {
            value.put("ingestionSource", asset.getIngestionSource());
        }
        if (asset.getLineageJson() != null) {
            value.put("observedParentage", bounded(asset.getLineageJson(), 4_000));
        }
        value.put("contentSha256", asset.getContentSha256());
        return value;
    }

    private EvidenceRef toEvidence(AiKnowledgeAsset asset) {
        AiEvidenceType type = asset.getType()
                == KnowledgeAssetType.VERIFIED_QUERY
                ? AiEvidenceType.VERIFIED_QUERY
                : AiEvidenceType.KNOWLEDGE_ASSET;
        java.time.Instant observedAt = asset.getUpdatedAt() == null
                ? java.time.Instant.now()
                : asset.getUpdatedAt().toInstant(ZoneOffset.UTC);
        return new EvidenceRef(asset.getId(), type, asset.getTitle(),
                "knowledge:" + asset.getId() + ":v" + asset.getAssetVersion(),
                asset.getContentSha256(), observedAt,
                EvidenceTrustLevel.VERIFIED);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化已验证知识上下文", exception);
        }
    }

    private static String contentDigest(AiKnowledgeAsset asset) {
        return AiDigest.sha256(String.join("\n",
                asset.getType().name(), asset.getTitle(), asset.getDefinition(),
                safe(asset.getVerifiedSql()), safe(asset.getDbType()),
                safe(asset.getGrantedSourceName()), safe(asset.getDefaultDatabase()),
                safe(asset.getKeywords()), safe(asset.getSourceRef()),
                safe(asset.getIngestionSource()),
                safe(asset.getLineageJson())));
    }

    private static AiKnowledgeAssetView view(AiKnowledgeAsset asset) {
        return new AiKnowledgeAssetView(
                asset.getId(), asset.getType(), asset.getStatus(),
                asset.getTitle(), asset.getDefinition(), asset.getVerifiedSql(),
                asset.getDbType(), asset.getGrantedSourceName(),
                asset.getDefaultDatabase(), splitKeywords(asset.getKeywords()),
                asset.getSourceRef(), asset.getIngestionSource(),
                asset.getLineageJson(), asset.getEmbeddingModel(),
                asset.getContentSha256(),
                asset.getAssetVersion(), asset.getCreatedBy(),
                asset.getReviewedBy(), asset.getReviewComment(),
                asset.getCreatedAt(), asset.getUpdatedAt(), asset.getReviewedAt());
    }

    private static String joinKeywords(List<String> keywords) {
        if (keywords == null) {
            return null;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String value = keyword.trim();
            if (value.length() > 100) {
                throw new IllegalArgumentException("单个关键词不得超过 100 字符");
            }
            normalized.add(value);
        }
        String joined = String.join(",", normalized);
        if (joined.length() > 1_000) {
            throw new IllegalArgumentException("关键词总长度不得超过 1000 字符");
        }
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return List.of(keywords.split(","));
    }

    private static KnowledgeAssetType requireType(KnowledgeAssetType type) {
        if (type == null) {
            throw new IllegalArgumentException("知识资产类型不能为空");
        }
        return type;
    }

    private static void requireStatus(
            AiKnowledgeAsset asset, KnowledgeAssetStatus expected) {
        if (asset.getStatus() != expected) {
            throw new IllegalStateException(
                    "知识资产当前状态不允许此操作: " + asset.getStatus());
        }
    }

    private static String requireWorkspace(String value) {
        return requireText(value, "工作空间 ID", 36);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "可选文本长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredAsset(AiKnowledgeAsset asset, double score) {
    }

    public record KnowledgeContext(
            String promptJson,
            List<EvidenceRef> evidence,
            List<String> omittedContext) {
        public KnowledgeContext {
            evidence = List.copyOf(evidence);
            omittedContext = List.copyOf(omittedContext);
        }
    }
}
