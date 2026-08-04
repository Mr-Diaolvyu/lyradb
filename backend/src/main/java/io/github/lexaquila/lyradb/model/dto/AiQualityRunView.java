package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.eval.AiEvaluationResult;

import java.time.LocalDateTime;
import java.util.List;

/** 一次可信 AI 回归的可审计结果。 */
public record AiQualityRunView(
        String id,
        String goldenSetVersion,
        String evaluationMode,
        String provider,
        String model,
        long durationMs,
        long totalTokens,
        int caseCount,
        int passedCount,
        double passRate,
        double averageScore,
        boolean releaseGatePassed,
        List<AiEvaluationResult> results,
        String createdBy,
        LocalDateTime createdAt) {

    public AiQualityRunView {
        results = List.copyOf(results == null ? List.of() : results);
    }
}
