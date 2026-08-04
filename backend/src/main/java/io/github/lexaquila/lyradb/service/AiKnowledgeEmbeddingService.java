package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 可选知识向量服务。任何 Provider 故障都显式降级，不影响关键词检索和审核。 */
@Service
public class AiKnowledgeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(
            AiKnowledgeEmbeddingService.class);
    private final AiProviderService providerService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public AiKnowledgeEmbeddingService(
            AiProviderService providerService,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.providerService = providerService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Optional<Embedding> embed(String workspaceId, String text) {
        AppProperties.Ai ai = properties.getAi();
        if (!ai.isKnowledgeSemanticEnabled()) {
            return Optional.empty();
        }
        String model = ai.getKnowledgeEmbeddingModel();
        if (model == null || model.isBlank()) {
            log.warn("知识向量检索已启用但未配置 Embedding 模型");
            return Optional.empty();
        }
        try {
            AiProviderConfig provider = providerService.resolveDefault(workspaceId);
            List<List<Double>> vectors = providerService.embed(
                    provider, model.trim(), List.of(bounded(text, 20_000)));
            return Optional.of(new Embedding(
                    model.trim(), vectors.get(0)));
        } catch (RuntimeException exception) {
            log.warn("知识向量生成失败，降级为关键词检索: workspace={}, type={}",
                    workspaceId, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public String serialize(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception exception) {
            throw new IllegalStateException("知识向量无法序列化", exception);
        }
    }

    public Optional<List<Double>> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            List<Double> vector = objectMapper.readValue(
                    json, new TypeReference<List<Double>>() { });
            if (vector.isEmpty() || vector.size() > 8_192
                    || vector.stream().anyMatch(value -> value == null
                            || !Double.isFinite(value))) {
                return Optional.empty();
            }
            return Optional.of(List.copyOf(vector));
        } catch (Exception exception) {
            log.warn("忽略损坏的知识向量");
            return Optional.empty();
        }
    }

    public static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty()
                || left.size() != right.size()) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double l = left.get(index);
            double r = right.get(index);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        double value = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return Math.max(0, Math.min(1, value));
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Embedding 文本不能为空");
        }
        String normalized = value.trim();
        return normalized.length() <= maximum
                ? normalized : normalized.substring(0, maximum);
    }

    public record Embedding(String model, List<Double> vector) {
        public Embedding {
            if (model == null || model.isBlank()
                    || vector == null || vector.isEmpty()) {
                throw new IllegalArgumentException("知识向量模型和内容不能为空");
            }
            vector = List.copyOf(vector);
        }
    }
}
