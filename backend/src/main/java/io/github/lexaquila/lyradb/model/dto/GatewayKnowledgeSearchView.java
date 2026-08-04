package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.EvidenceRef;

import java.util.List;

/** 仅包含审核通过知识的 Gateway 检索结果。 */
public record GatewayKnowledgeSearchView(
        String verifiedContextJson,
        List<EvidenceRef> evidence,
        List<String> omittedContext) {

    public GatewayKnowledgeSearchView {
        evidence = List.copyOf(evidence);
        omittedContext = List.copyOf(omittedContext);
    }
}
