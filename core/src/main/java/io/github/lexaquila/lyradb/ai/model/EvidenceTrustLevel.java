package io.github.lexaquila.lyradb.ai.model;

/** 证据可信等级；生成内容不得伪装为已验证事实。 */
public enum EvidenceTrustLevel {
    GENERATED,
    OBSERVED,
    CURATED,
    VERIFIED
}
