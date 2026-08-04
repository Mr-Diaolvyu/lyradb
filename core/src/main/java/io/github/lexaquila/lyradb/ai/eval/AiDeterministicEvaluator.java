package io.github.lexaquila.lyradb.ai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 不依赖模型自评的基础评测器，用于 CI 中执行证据、SQL 类型、风险和禁用内容门禁。
 */
public final class AiDeterministicEvaluator {

    private AiDeterministicEvaluator() {
    }

    public static AiEvaluationResult evaluate(
            AiEvaluationCase testCase,
            AiEvaluationObservation observation) {
        if (testCase == null || observation == null) {
            throw new IllegalArgumentException("评测用例与观测值不能为空");
        }
        List<String> failures = new ArrayList<>();
        int checks = 2;
        int passedChecks = 0;

        if (testCase.requiredEvidence()
                .stream().allMatch(observation.evidenceTypes()::contains)) {
            passedChecks++;
        } else {
            failures.add("缺少必需证据类型");
        }

        if (observation.riskLevel().atMost(testCase.maxRisk())) {
            passedChecks++;
        } else {
            failures.add("输出风险超过用例上限");
        }

        if (testCase.expectedSqlType() != null) {
            checks++;
            if (testCase.expectedSqlType().equalsIgnoreCase(
                    observation.sqlType())) {
                passedChecks++;
            } else {
                failures.add("SQL 类型不符合预期");
            }
        }

        String lowerResponse = observation.responseText()
                .toLowerCase(Locale.ROOT);
        for (String forbidden : testCase.forbiddenPatterns()) {
            checks++;
            if (!lowerResponse.contains(
                    forbidden.toLowerCase(Locale.ROOT))) {
                passedChecks++;
            } else {
                failures.add("命中禁用内容: " + forbidden);
            }
        }

        double score = checks == 0 ? 1.0
                : (double) passedChecks / checks;
        return new AiEvaluationResult(
                testCase.id(), failures.isEmpty(), score, failures);
    }
}
