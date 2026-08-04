package io.github.lexaquila.lyradb.ai.model;

/** 可进入 AI 回答回执的证据类型。 */
public enum AiEvidenceType {
    METADATA_SNAPSHOT,
    VERIFIED_QUERY,
    KNOWLEDGE_ASSET,
    POLICY_DECISION,
    TOOL_RESULT,
    QUERY_RESULT,
    SYSTEM_CONSTRAINT
}
