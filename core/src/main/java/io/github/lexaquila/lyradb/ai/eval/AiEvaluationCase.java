package io.github.lexaquila.lyradb.ai.eval;

import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;

import java.util.List;
import java.util.Set;

/** 可版本化的 AI 黄金集用例。 */
public record AiEvaluationCase(
        String id,
        String category,
        String question,
        String expectedSqlType,
        Set<AiEvidenceType> requiredEvidence,
        List<String> forbiddenPatterns,
        AgentRiskLevel maxRisk) {

    public AiEvaluationCase {
        id = requireText(id, "用例 ID", 128);
        category = requireText(category, "用例分类", 100);
        question = requireText(question, "问题", 20_000);
        expectedSqlType = optionalText(expectedSqlType, 32);
        requiredEvidence = Set.copyOf(
                requiredEvidence == null ? Set.of() : requiredEvidence);
        forbiddenPatterns = List.copyOf(
                forbiddenPatterns == null ? List.of() : forbiddenPatterns);
        if (forbiddenPatterns.stream()
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("禁用模式不得包含空值");
        }
        if (maxRisk == null) {
            throw new IllegalArgumentException("用例风险上限不能为空");
        }
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
