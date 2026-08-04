package io.github.lexaquila.lyradb.ai.tool;

import io.github.lexaquila.lyradb.ai.AiDigest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPermissionEnvelopeTest {

    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");
    private static final AgentToolDefinition READ_TOOL =
            new AgentToolDefinition("sql.execute.readonly", "1",
                    "执行已确认的只读查询", AgentToolEffect.READ_DATA,
                    true, true);

    @Test
    void exactTaskScopeIsAllowed() {
        PolicyDecision decision = envelope().authorize(READ_TOOL,
                call(Set.of("sales.orders"), 100, 2_000), NOW);

        assertTrue(decision.allowed());
        assertEquals("ALLOW", decision.code());
    }

    @Test
    void rejectsResourceRowCostRiskAndExpiryViolations() {
        assertDenied("RESOURCE_NOT_ALLOWED",
                envelope().authorize(READ_TOOL,
                        call(Set.of("sales.secrets"), 100, 2_000), NOW));
        assertDenied("ROW_LIMIT_EXCEEDED",
                envelope().authorize(READ_TOOL,
                        call(Set.of("sales.orders"), 101, 2_000), NOW));
        assertDenied("COST_LIMIT_EXCEEDED",
                envelope().authorize(READ_TOOL,
                        call(Set.of("sales.orders"), 100, 10_001), NOW));

        AgentToolDefinition writeTool = new AgentToolDefinition(
                "sql.execute.write", "1", "执行写入",
                AgentToolEffect.WRITE_DATA, false, false);
        AgentToolCall writeCall = new AgentToolCall(
                "call-2", "run-1", "sql.execute.write",
                Set.of("sales.orders"), 1, 1, AiDigest.sha256("write"));
        AgentPermissionEnvelope writeNamed = new AgentPermissionEnvelope(
                "user-1", "workspace-1", "grant-1",
                Set.of("sql.execute.write"), Set.of("sales.orders"),
                100, 10_000, NOW.plusSeconds(60), AgentRiskLevel.R2);
        assertDenied("RISK_EXCEEDED",
                writeNamed.authorize(writeTool, writeCall, NOW));

        assertDenied("ENVELOPE_EXPIRED",
                envelope().authorize(READ_TOOL,
                        call(Set.of("sales.orders"), 1, 1),
                        NOW.plusSeconds(61)));
    }

    private static AgentPermissionEnvelope envelope() {
        return new AgentPermissionEnvelope(
                "user-1", "workspace-1", "grant-1",
                Set.of("sql.execute.readonly"), Set.of("sales.orders"),
                100, 10_000, NOW.plusSeconds(60), AgentRiskLevel.R2);
    }

    private static AgentToolCall call(
            Set<String> resources, int rows, long cost) {
        return new AgentToolCall("call-1", "run-1",
                "sql.execute.readonly", resources, rows, cost,
                AiDigest.sha256("arguments"));
    }

    private static void assertDenied(String code, PolicyDecision decision) {
        assertFalse(decision.allowed());
        assertEquals(code, decision.code());
    }
}
