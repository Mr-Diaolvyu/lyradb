package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** 创建受治理只读 Agent 计划。 */
@Data
public class AiReadAgentPlanRequest {
    private String grantedSourceName;
    private String question;
    private String sql;
    private String defaultDatabase;
    private Integer requestedRows;
    private Long estimatedCostMicros;
    private String maxComputePreflightSha256;
}
