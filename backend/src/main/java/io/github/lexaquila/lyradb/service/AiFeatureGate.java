package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * AI 功能开关的服务端唯一判定点。前端隐藏入口不构成安全边界。
 */
@Service
public class AiFeatureGate {

    private final AppProperties properties;

    public AiFeatureGate(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validateConfiguration() {
        AppProperties.Ai ai = properties.getAi();
        if (ai.isWriteAgentEnabled()) {
            throw new IllegalStateException(
                    "LyraDB 3.x 写入型 Agent 硬门禁尚未解除，禁止启用");
        }
        if (ai.isGovernedReadAgentEnabled()
                && !ai.isAskLyraEnabled()) {
            throw new IllegalStateException(
                    "受治理只读 Agent 必须依赖 Ask Lyra");
        }
        if (ai.isTeamKnowledgeLoopEnabled()
                && !ai.isKnowledgeCoreEnabled()) {
            throw new IllegalStateException(
                    "团队知识闭环必须依赖 Knowledge Core");
        }
        if (ai.isAgentGatewayEnabled()
                && (!ai.isKnowledgeCoreEnabled()
                || !ai.isGovernedReadAgentEnabled())) {
            throw new IllegalStateException(
                    "Agent Gateway 必须依赖 Knowledge Core 与受治理只读 Agent");
        }
        if (ai.isMaxComputeAgentEnabled()
                && !ai.isGovernedReadAgentEnabled()) {
            throw new IllegalStateException(
                    "MaxCompute Agent 必须依赖受治理只读 Agent");
        }
        if (ai.getMaxKnowledgeContextChars() < 1_000
                || ai.getMaxKnowledgeContextChars() > 100_000) {
            throw new IllegalStateException(
                    "AI 知识上下文上限必须位于 1000 到 100000 字符之间");
        }
        if (ai.getKnowledgeLexicalWeight() < 0
                || ai.getKnowledgeLexicalWeight() > 1) {
            throw new IllegalStateException(
                    "知识混合检索关键词权重必须位于 0 到 1 之间");
        }
        if (ai.isKnowledgeSemanticEnabled()
                && (!ai.isKnowledgeCoreEnabled()
                || ai.getKnowledgeEmbeddingModel() == null
                || ai.getKnowledgeEmbeddingModel().isBlank()
                || ai.getKnowledgeEmbeddingModel().length() > 200)) {
            throw new IllegalStateException(
                    "知识向量检索必须启用 Knowledge Core 并配置有效 Embedding 模型");
        }
        if (ai.isMaxComputeLiveEvidenceRequired()
                && (!ai.isMaxComputeLiveEvidenceEnabled()
                || !ai.isMaxComputeAgentEnabled())) {
            throw new IllegalStateException(
                    "强制 MaxCompute 实时证据必须同时启用实时证据和 MaxCompute Agent");
        }
        if (ai.getGatewayRequestsPerMinute() < 1
                || ai.getGatewayRequestsPerMinute() > 10_000
                || ai.getGatewayExpensiveRequestsPerMinute() < 1
                || ai.getGatewayExpensiveRequestsPerMinute()
                > ai.getGatewayRequestsPerMinute()) {
            throw new IllegalStateException(
                    "Gateway 限流必须位于 1-10000，且高成本上限不能超过普通上限");
        }
        if (ai.getMcpMaxRequestBytes() < 1_024
                || ai.getMcpMaxRequestBytes() > 5 * 1_024 * 1_024) {
            throw new IllegalStateException(
                    "MCP 请求上限必须位于 1 KiB 到 5 MiB 之间");
        }
        if (ai.isPrivateModelEnabled()
                && (ai.getPrivateModelAllowedHosts() == null
                || ai.getPrivateModelAllowedHosts().isBlank())) {
            throw new IllegalStateException(
                    "启用私有模型时必须配置精确主机白名单");
        }
        if (ai.getExecutionNodeId() != null
                && ai.getExecutionNodeId().length() > 128) {
            throw new IllegalStateException(
                    "AI 执行节点 ID 不得超过 128 字符");
        }
        if (ai.getCancelPollIntervalMs() < 250
                || ai.getCancelPollIntervalMs() > 60_000) {
            throw new IllegalStateException(
                    "跨节点取消轮询间隔必须位于 250 到 60000 毫秒之间");
        }
        if (ai.getReadAgentPlanTtlSeconds() < 30
                || ai.getReadAgentPlanTtlSeconds() > 3_600) {
            throw new IllegalStateException(
                    "只读 Agent 计划有效期必须位于 30 到 3600 秒之间");
        }
        if (ai.getReadAgentMaxRows() < 1
                || ai.getReadAgentMaxRows() > 10_000) {
            throw new IllegalStateException(
                    "只读 Agent 行数上限必须位于 1 到 10000 之间");
        }
        if (ai.getReadAgentMaxEstimatedCostMicros() < 0) {
            throw new IllegalStateException("只读 Agent 成本预算不能为负数");
        }
    }

    public void requireEnabled(AiFeature feature) {
        if (!isEnabled(feature)) {
            throw new IllegalStateException(
                    "AI 能力尚未启用: " + feature.name());
        }
    }

    public boolean isEnabled(AiFeature feature) {
        AppProperties.Ai ai = properties.getAi();
        return switch (feature) {
            case ASK_LYRA -> ai.isAskLyraEnabled();
            case KNOWLEDGE_CORE -> ai.isKnowledgeCoreEnabled();
            case GOVERNED_READ_AGENT -> ai.isGovernedReadAgentEnabled();
            case TEAM_KNOWLEDGE_LOOP -> ai.isTeamKnowledgeLoopEnabled();
            case AI_QUALITY -> ai.isQualityEnabled();
            case MAXCOMPUTE_AGENT -> ai.isMaxComputeAgentEnabled();
            case AGENT_GATEWAY -> ai.isAgentGatewayEnabled();
            case WRITE_AGENT -> false;
        };
    }

    public Map<AiFeature, Boolean> snapshot() {
        EnumMap<AiFeature, Boolean> values = new EnumMap<>(AiFeature.class);
        for (AiFeature feature : AiFeature.values()) {
            values.put(feature, isEnabled(feature));
        }
        return Map.copyOf(values);
    }
}
