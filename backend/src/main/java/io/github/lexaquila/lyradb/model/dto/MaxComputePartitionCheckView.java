package io.github.lexaquila.lyradb.model.dto;

import java.util.Set;

/** 单表分区谓词覆盖结果。 */
public record MaxComputePartitionCheckView(
        String table,
        Set<String> requiredColumns,
        Set<String> matchedColumns,
        boolean covered) {

    public MaxComputePartitionCheckView {
        requiredColumns = Set.copyOf(requiredColumns);
        matchedColumns = Set.copyOf(matchedColumns);
    }
}
