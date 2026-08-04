package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiOperationsServiceTest {

    @Mock
    private AiAgentRunRepository repository;

    @Test
    void combinesProcessMetricsWithWorkspaceScopedDurableRuns() {
        AiOperationalMetrics metrics = new AiOperationalMetrics();
        metrics.record(AiOperationalMetrics.Operation.PROVIDER_CHAT,
                true, 30);
        metrics.record(AiOperationalMetrics.Operation.PROVIDER_CHAT,
                false, 10);
        when(repository.countByWorkspaceIdAndStatus(
                eq("workspace-1"), anyString())).thenReturn(0L);
        when(repository.countByWorkspaceIdAndStatus(
                "workspace-1", "RUNNING")).thenReturn(2L);

        Map<String, Object> snapshot = new AiOperationsService(
                metrics, repository).snapshot("workspace-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> process = (Map<String, Object>)
                snapshot.get("processMetrics");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> operations =
                (Map<String, Map<String, Object>>) process.get("operations");
        assertEquals(2L, operations.get("provider.chat").get("calls"));
        assertEquals(1L, operations.get("provider.chat").get("failures"));
        assertEquals(20D,
                operations.get("provider.chat").get("averageDurationMs"));

        @SuppressWarnings("unchecked")
        Map<String, Long> durable = (Map<String, Long>)
                snapshot.get("durableReadAgentRuns");
        assertEquals(2L, durable.get("RUNNING"));
        assertTrue(durable.containsKey("EXPIRED"));
    }
}
