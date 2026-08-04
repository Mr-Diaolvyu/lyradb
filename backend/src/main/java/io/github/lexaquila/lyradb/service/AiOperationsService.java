package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 合并进程级调用指标与工作空间隔离的持久运行状态。 */
@Service
public class AiOperationsService {

    private static final List<String> RUN_STATUSES = List.of(
            "PLANNED", "RUNNING", "CANCEL_REQUESTED", "COMPLETED",
            "FAILED", "CANCELLED", "EXPIRED");

    private final AiOperationalMetrics metrics;
    private final AiAgentRunRepository runRepository;

    public AiOperationsService(
            AiOperationalMetrics metrics,
            AiAgentRunRepository runRepository) {
        this.metrics = metrics;
        this.runRepository = runRepository;
    }

    public Map<String, Object> snapshot(String workspaceId) {
        Map<String, Long> durableRuns = new LinkedHashMap<>();
        for (String status : RUN_STATUSES) {
            durableRuns.put(status,
                    runRepository.countByWorkspaceIdAndStatus(
                            workspaceId, status));
        }
        return Map.of(
                "processMetrics", metrics.snapshot(),
                "durableReadAgentRuns", Map.copyOf(durableRuns));
    }
}
