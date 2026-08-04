package io.github.lexaquila.lyradb.ai.eval;

import java.util.List;

/** 单条黄金集用例的确定性评分结果。 */
public record AiEvaluationResult(
        String caseId,
        boolean passed,
        double score,
        List<String> failures) {

    public AiEvaluationResult {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("用例 ID 不能为空");
        }
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("评分必须位于 0 到 1 之间");
        }
        failures = List.copyOf(failures == null ? List.of() : failures);
        if (passed && !failures.isEmpty()) {
            throw new IllegalArgumentException("通过的用例不能包含失败原因");
        }
    }
}
