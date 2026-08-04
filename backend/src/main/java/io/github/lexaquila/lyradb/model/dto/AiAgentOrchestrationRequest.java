package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/** Ask Lyra 受限工具编排请求。所有授权身份均由服务端会话重新解析。 */
@Data
public class AiAgentOrchestrationRequest {
    private String grantedSourceName;
    private String question;
    private String metadataSnapshotId;
    private String defaultDatabase;
    private Integer requestedRows;
    private Long estimatedCostMicros;
    private String maxComputePreflightSha256;
}
