package io.github.lexaquila.lyradb.ai.model;

import io.github.lexaquila.lyradb.ai.AiDigest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次 AI 请求实际使用了什么上下文、应用了什么策略的不可变回执。
 */
public record AiContextReceipt(
        String requestId,
        String workspaceId,
        String purpose,
        String provider,
        String model,
        Instant createdAt,
        List<EvidenceRef> evidence,
        List<String> appliedPolicies,
        List<String> omittedContext,
        String contextSha256) {

    public AiContextReceipt {
        requestId = requireText(requestId, "请求 ID", 128);
        workspaceId = requireText(workspaceId, "工作空间 ID", 128);
        purpose = requireText(purpose, "请求目的", 200);
        provider = optionalText(provider, 100);
        model = optionalText(model, 200);
        if (createdAt == null) {
            throw new IllegalArgumentException("回执时间不能为空");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        appliedPolicies = normalizedList(appliedPolicies);
        omittedContext = normalizedList(omittedContext);
        String calculated = calculateDigest(requestId, workspaceId, purpose,
                provider, model, evidence, appliedPolicies, omittedContext);
        if (contextSha256 == null || contextSha256.isBlank()) {
            contextSha256 = calculated;
        } else if (!calculated.equalsIgnoreCase(contextSha256.trim())) {
            throw new IllegalArgumentException("上下文回执摘要与内容不一致");
        } else {
            contextSha256 = calculated;
        }
    }

    public static AiContextReceipt create(
            String requestId,
            String workspaceId,
            String purpose,
            String provider,
            String model,
            Instant createdAt,
            List<EvidenceRef> evidence,
            List<String> appliedPolicies,
            List<String> omittedContext) {
        return new AiContextReceipt(requestId, workspaceId, purpose, provider,
                model, createdAt, evidence, appliedPolicies, omittedContext, null);
    }

    private static String calculateDigest(
            String requestId, String workspaceId, String purpose,
            String provider, String model, List<EvidenceRef> evidence,
            List<String> policies, List<String> omitted) {
        List<String> evidenceTokens = new ArrayList<>();
        for (EvidenceRef item : evidence) {
            evidenceTokens.add(item.id() + ":" + item.type() + ":"
                    + item.contentSha256());
        }
        evidenceTokens.sort(Comparator.naturalOrder());
        return AiDigest.sha256(String.join("\n",
                requestId, workspaceId, purpose,
                provider == null ? "" : provider,
                model == null ? "" : model,
                String.join(",", evidenceTokens),
                String.join(",", policies),
                String.join(",", omitted)));
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("可选文本长度不得超过 " + maxLength);
        }
        return value.trim();
    }
}
