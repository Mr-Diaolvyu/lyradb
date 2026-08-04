package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** 自动黄金集回归需要显式确认会产生外部模型调用与可能费用。 */
@Data
public class AiQualityAutoEvaluationRequest {
    private boolean acknowledgeProviderUsage;
}
