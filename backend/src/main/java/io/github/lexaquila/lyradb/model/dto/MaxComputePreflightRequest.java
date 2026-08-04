package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** MaxCompute 专项预检；分区声明必须来自调用方已核验的元数据。 */
@Data
public class MaxComputePreflightRequest {
    private String grantedSourceName;
    private String sql;
    private String defaultDatabase;
    private Map<String, List<String>> requiredPartitionColumns;
    private Long estimatedInputBytes;
    private Long estimatedCostMicros;
}
