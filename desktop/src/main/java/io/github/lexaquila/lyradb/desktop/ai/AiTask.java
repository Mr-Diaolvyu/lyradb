package io.github.lexaquila.lyradb.desktop.ai;

/**
 * 个人版 AI 助手支持的数据库任务。
 */
public enum AiTask {
    GENERATE("生成 SQL", "根据用户目标生成一条可执行 SQL，并解释关键假设"),
    EXPLAIN("解释 SQL", "逐段解释 SQL 的逻辑、输入输出、过滤条件和潜在影响"),
    FIX("修复 SQL", "定位 SQL 错误并给出修复后的完整 SQL"),
    OPTIMIZE("优化 SQL", "在不改变业务语义的前提下优化 SQL，并说明收益与风险"),
    REVIEW("安全审查", "审查 SQL 的正确性、性能、数据安全和不可逆风险");

    private final String displayName;
    private final String instruction;

    AiTask(String displayName, String instruction) {
        this.displayName = displayName;
        this.instruction = instruction;
    }

    public String displayName() {
        return displayName;
    }

    public String instruction() {
        return instruction;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
