package io.github.lexaquila.lyradb.model.dto;

/** 从元数据快照形成的一条待审核知识草稿摘要。 */
public record AiKnowledgeIngestedDraftView(
        String id,
        String title,
        String sourceRef,
        String status) {
}
