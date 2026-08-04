package io.github.lexaquila.lyradb.ai.knowledge;

/** 知识资产审核状态；只有 VERIFIED 可进入模型上下文。 */
public enum KnowledgeAssetStatus {
    DRAFT,
    IN_REVIEW,
    VERIFIED,
    REJECTED,
    RETIRED
}
