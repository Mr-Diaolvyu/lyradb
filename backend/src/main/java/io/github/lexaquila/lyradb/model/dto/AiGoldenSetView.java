package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.eval.AiEvaluationCase;

import java.util.List;

/** 可版本化的可信 AI 黄金集目录。 */
public record AiGoldenSetView(
        String version,
        String description,
        List<AiEvaluationCase> cases) {

    public AiGoldenSetView {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("黄金集版本不能为空");
        }
        description = description == null ? "" : description.trim();
        cases = List.copyOf(cases == null ? List.of() : cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("黄金集至少包含一个用例");
        }
    }
}
