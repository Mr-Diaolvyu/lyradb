package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;

import java.util.List;

/** 确定性任务诊断，不自动重试、不执行 SQL。 */
public record MaxComputeDiagnosticView(
        String normalizedStatus,
        String category,
        String summary,
        List<String> recommendations,
        boolean automaticRetryAllowed,
        AiContextReceipt contextReceipt) {

    public MaxComputeDiagnosticView {
        recommendations = List.copyOf(recommendations);
    }
}
