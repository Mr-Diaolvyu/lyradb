package io.github.lexaquila.lyradb.model.dto;

import java.util.List;

/** MaxCompute 分区的有界分页响应。 */
public record EnterprisePartitionPageView(
        List<String> partitionColumns,
        List<String> partitions,
        int offset,
        int limit,
        boolean hasMore,
        boolean truncated,
        String suggestedPartition,
        String ordering,
        String filter,
        String metadataSource,
        String metadataStatus,
        String metadataReason) {

    public EnterprisePartitionPageView {
        partitionColumns = partitionColumns == null
                ? List.of() : List.copyOf(partitionColumns);
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        filter = filter == null ? "" : filter;
    }
}
