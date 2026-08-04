package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** 调用方提供的 MaxCompute 任务状态摘要；不包含凭据和结果数据。 */
@Data
public class MaxComputeDiagnosticRequest {
    private String taskStatus;
    private String errorCode;
    private String errorMessage;
}
