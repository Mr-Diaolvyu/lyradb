package io.github.lexaquila.lyradb.ai.tool;

/** 类型化工具声明的外部影响。 */
public enum AgentToolEffect {
    LOCAL_ONLY(AgentRiskLevel.R0),
    READ_METADATA(AgentRiskLevel.R1),
    READ_DATA(AgentRiskLevel.R2),
    WRITE_DATA(AgentRiskLevel.R3),
    WRITE_SCHEMA(AgentRiskLevel.R4),
    EXTERNAL_WRITE(AgentRiskLevel.R4);

    private final AgentRiskLevel riskLevel;

    AgentToolEffect(AgentRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public AgentRiskLevel riskLevel() {
        return riskLevel;
    }
}
