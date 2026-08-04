package io.github.lexaquila.lyradb.ai.maxcompute;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxComputePartitionInspectorTest {

    @Test
    void aliasQualifiedWhereCoversDeclaredPartition() {
        var result = MaxComputePartitionInspector.inspect(
                "SELECT o.id FROM sales.orders o WHERE o.ds = '20260803'",
                Map.of("sales.orders", List.of("ds")));

        assertTrue(result.partitionChecks().get(0).covered());
    }

    @Test
    void unqualifiedColumnAcrossJoinIsConservativelyRejected() {
        var result = MaxComputePartitionInspector.inspect(
                "SELECT o.id FROM sales.orders o JOIN sales.users u "
                        + "ON o.user_id = u.id WHERE ds = '20260803'",
                Map.of("sales.orders", List.of("ds"),
                        "sales.users", List.of("ds")));

        assertFalse(result.partitionChecks().get(0).covered());
        assertFalse(result.partitionChecks().get(1).covered());
    }

    @Test
    void literalMentionDoesNotCountAsPartitionPredicate() {
        var result = MaxComputePartitionInspector.inspect(
                "SELECT o.id FROM sales.orders o WHERE o.note = 'ds=20260803'",
                Map.of("sales.orders", List.of("ds")));

        assertFalse(result.partitionChecks().get(0).covered());
    }
}
