package io.github.lexaquila.lyradb.model.dto;

/** 质量仪表：当前黄金集与当前工作空间最近一次完整回归。 */
public record AiQualityDashboardView(
        AiGoldenSetView goldenSet,
        AiQualityRunView latestRun) {
}
