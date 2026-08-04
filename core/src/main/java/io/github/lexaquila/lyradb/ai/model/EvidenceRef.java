package io.github.lexaquila.lyradb.ai.model;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AI 回答中的证据引用。只保存定位信息和内容摘要，不携带凭据或完整数据正文。
 */
public record EvidenceRef(
        String id,
        AiEvidenceType type,
        String title,
        String sourceRef,
        String contentSha256,
        Instant observedAt,
        EvidenceTrustLevel trustLevel) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public EvidenceRef {
        id = requireText(id, "证据 ID", 128);
        if (type == null) {
            throw new IllegalArgumentException("证据类型不能为空");
        }
        title = requireText(title, "证据标题", 300);
        sourceRef = requireText(sourceRef, "证据来源", 1_000);
        contentSha256 = requireText(contentSha256, "证据摘要", 64)
                .toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("证据摘要必须是 64 位 SHA-256");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("证据观测时间不能为空");
        }
        if (trustLevel == null) {
            throw new IllegalArgumentException("证据可信等级不能为空");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }
}
