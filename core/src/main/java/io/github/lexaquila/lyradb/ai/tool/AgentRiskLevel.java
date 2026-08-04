package io.github.lexaquila.lyradb.ai.tool;

/** Agent 行为风险等级。 */
public enum AgentRiskLevel {
    R0(0),
    R1(1),
    R2(2),
    R3(3),
    R4(4);

    private final int level;

    AgentRiskLevel(int level) {
        this.level = level;
    }

    public boolean atMost(AgentRiskLevel ceiling) {
        return ceiling != null && level <= ceiling.level;
    }
}
