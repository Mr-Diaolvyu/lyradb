package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

import java.util.List;

/** 一次完整黄金集回归请求；禁止只提交有利子集。 */
@Data
public class AiQualityEvaluationRequest {
    private List<AiQualityObservationRequest> observations;
}
