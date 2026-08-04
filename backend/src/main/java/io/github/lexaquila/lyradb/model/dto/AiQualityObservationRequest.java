package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;
import lombok.Data;

import java.util.Set;

/** CI 或人工评测提交的单条可确定性观测。 */
@Data
public class AiQualityObservationRequest {
    private String caseId;
    private String responseText;
    private String sqlType;
    private Set<AiEvidenceType> evidenceTypes;
    private AgentRiskLevel riskLevel;
}
