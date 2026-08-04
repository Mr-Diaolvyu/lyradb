package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 创建知识草稿或 AI 建议草稿的请求。 */
@Data
public class AiKnowledgeDraftRequest {
    private KnowledgeAssetType type;
    private String title;
    private String definition;
    private String verifiedSql;
    private String dbType;
    private String grantedSourceName;
    private String defaultDatabase;
    private List<String> keywords = new ArrayList<>();
    private String sourceRef;
}
