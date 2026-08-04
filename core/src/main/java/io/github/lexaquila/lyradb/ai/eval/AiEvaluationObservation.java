package io.github.lexaquila.lyradb.ai.eval;

import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;

import java.util.Set;

/** 一次 AI 输出中可被确定性规则核验的观测值。 */
public record AiEvaluationObservation(
        String responseText,
        String sqlType,
        Set<AiEvidenceType> evidenceTypes,
        AgentRiskLevel riskLevel) {

    public AiEvaluationObservation {
        responseText = responseText == null ? "" : responseText;
        sqlType = sqlType == null || sqlType.isBlank()
                ? null : sqlType.trim().toUpperCase(java.util.Locale.ROOT);
        evidenceTypes = Set.copyOf(
                evidenceTypes == null ? Set.of() : evidenceTypes);
        if (riskLevel == null) {
            throw new IllegalArgumentException("观测风险等级不能为空");
        }
    }
}
