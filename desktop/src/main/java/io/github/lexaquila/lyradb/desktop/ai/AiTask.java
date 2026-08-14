package io.github.lexaquila.lyradb.desktop.ai;

/**
 * 智库助手支持的数据库工作任务。
 */
public enum AiTask {
    FIND_TABLE(
            "智能找表",
            "只允许依据用户已明确附加的元数据推荐候选表，输出表名、"
                    + "元数据中已有的注释或业务含义、匹配理由和信息边界；"
                    + "元数据不足时明确说明，"
                    + "禁止编造任何表、字段、注释或血缘",
            "输入业务词或想解决的问题，例如“项目客户到访明细在哪里”",
            true,
            true),
    EXPLAIN_TABLE(
            "解释表",
            "只依据用户已明确附加的元数据解释表用途、关键字段、分区和已知关系；"
                    + "未提供的业务口径必须标为未知，禁止根据字段名猜测",
            "说明你想了解哪张表，或希望重点解释哪些字段和口径",
            true,
            false),
    GENERATE(
            "生成 SQL",
            "根据用户目标生成一条可执行 SQL，并解释关键假设",
            "描述目标、过滤条件、输出字段和期望口径",
            false,
            false),
    OPTIMIZE(
            "优化 SQL",
            "在不改变业务语义的前提下优化 SQL，并说明收益与风险",
            "说明性能问题或优化目标；当前编辑器 SQL 会自动作为上下文",
            false,
            false),
    FIX(
            "诊断报错",
            "定位 SQL 或数据库命令的错误，解释原因并给出修复后的完整内容",
            "粘贴报错信息并说明期望结果；当前编辑器 SQL 会自动作为上下文",
            false,
            false),
    LINEAGE_IMPACT(
            "血缘影响",
            "只依据用户已明确附加的元数据分析上下游和变更影响；附件未包含血缘时"
                    + "必须明确说明无法判断，禁止编造依赖关系",
            "描述准备修改的表、字段或逻辑，以及希望评估的影响范围",
            true,
            false),
    DATA_QUALITY(
            "数据质量",
            "依据已提供的表结构、字段口径和当前 SQL 给出可复核的数据质量检查建议；"
                    + "优先覆盖非空率、枚举分布、唯一性、重复值和分区完整性，"
                    + "不得声称已经检查真实数据",
            "说明要检查的表、关键字段和质量目标",
            true,
            false),
    EXPLAIN(
            "解释 SQL",
            "逐段解释 SQL 的逻辑、输入输出、过滤条件和潜在影响",
            "说明最关心的逻辑；当前编辑器 SQL 会自动作为上下文",
            false,
            false),
    REVIEW(
            "安全审查",
            "审查 SQL 的正确性、性能、数据安全和不可逆风险",
            "说明审查重点；当前编辑器 SQL 会自动作为上下文",
            false,
            false);

    private final String displayName;
    private final String instruction;
    private final String requestHint;
    private final boolean requiresMetadata;
    private final boolean requiresRequest;

    AiTask(String displayName, String instruction, String requestHint,
            boolean requiresMetadata, boolean requiresRequest) {
        this.displayName = displayName;
        this.instruction = instruction;
        this.requestHint = requestHint;
        this.requiresMetadata = requiresMetadata;
        this.requiresRequest = requiresRequest;
    }

    public String displayName() {
        return displayName;
    }

    public String instruction() {
        return instruction;
    }

    public String requestHint() {
        return requestHint;
    }

    public boolean requiresMetadata() {
        return requiresMetadata;
    }

    public boolean requiresRequest() {
        return requiresRequest;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
