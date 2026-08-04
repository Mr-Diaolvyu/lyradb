package io.github.lexaquila.lyradb.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 可供用户确认的只读 Agent 计划。 */
public record AiReadAgentPlanView(
        String runId,
        String planSha256,
        String grantedSourceName,
        String sql,
        String defaultDatabase,
        Set<String> resources,
        int maxRows,
        long estimatedCostMicros,
        String riskLevel,
        Instant expiresAt,
        List<String> steps,
        boolean confirmationRequired) {
}
