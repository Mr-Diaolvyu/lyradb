package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** MaxCompute 分区、成本与授权预检结果。 */
public record MaxComputePreflightView(
        boolean planEligible,
        String decision,
        String preflightSha256,
        Instant expiresAt,
        Set<String> resources,
        String evidenceMode,
        MaxComputeLiveEvidenceView liveEvidence,
        List<MaxComputePartitionCheckView> partitionChecks,
        Long estimatedInputBytes,
        long estimatedCostMicros,
        long costBudgetMicros,
        String costStatus,
        List<String> warnings,
        AiContextReceipt contextReceipt) {

    public MaxComputePreflightView {
        resources = Set.copyOf(resources);
        partitionChecks = List.copyOf(partitionChecks);
        warnings = List.copyOf(warnings);
    }
}
