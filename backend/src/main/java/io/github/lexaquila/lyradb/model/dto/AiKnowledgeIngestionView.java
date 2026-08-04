package io.github.lexaquila.lyradb.model.dto;

import java.util.List;

/** 元数据摄取回执。所有产物固定为 DRAFT，不能直接进入模型可信上下文。 */
public record AiKnowledgeIngestionView(
        String snapshotId,
        String grantedSourceName,
        int createdDrafts,
        int omittedTables,
        List<AiKnowledgeIngestedDraftView> drafts,
        boolean reviewRequired) {

    public AiKnowledgeIngestionView {
        drafts = List.copyOf(drafts == null ? List.of() : drafts);
    }
}
