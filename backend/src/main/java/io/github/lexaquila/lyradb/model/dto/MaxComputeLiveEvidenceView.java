package io.github.lexaquila.lyradb.model.dto;

import java.util.List;
import java.util.Map;

/** MaxCompute 实时只读证据；仅含元数据、计划和费用摘要，不含业务数据行。 */
public record MaxComputeLiveEvidenceView(
        String status,
        Map<String, List<String>> partitionColumns,
        Long estimatedInputBytes,
        Long estimatedCostMicros,
        String explainSha256,
        String costCommandSha256,
        List<String> warnings) {

    public MaxComputeLiveEvidenceView {
        partitionColumns = partitionColumns == null ? Map.of()
                : partitionColumns.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
