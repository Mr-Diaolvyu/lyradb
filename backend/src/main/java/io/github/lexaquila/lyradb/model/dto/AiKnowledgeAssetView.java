package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;

import java.time.LocalDateTime;
import java.util.List;

/** 不暴露内部锁字段的知识资产 API 视图。 */
public record AiKnowledgeAssetView(
        String id,
        KnowledgeAssetType type,
        KnowledgeAssetStatus status,
        String title,
        String definition,
        String verifiedSql,
        String dbType,
        String grantedSourceName,
        String defaultDatabase,
        List<String> keywords,
        String sourceRef,
        String ingestionSource,
        String lineageJson,
        String embeddingModel,
        String contentSha256,
        int version,
        String createdBy,
        String reviewedBy,
        String reviewComment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime reviewedAt) {
}
