package io.github.lexaquila.lyradb.ai.model;

import io.github.lexaquila.lyradb.ai.AiDigest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiContextReceiptTest {

    @Test
    void digestIsStableAndCollectionsAreImmutable() {
        Instant now = Instant.parse("2026-08-03T04:00:00Z");
        EvidenceRef first = evidence("b", now);
        EvidenceRef second = evidence("a", now);
        ArrayList<EvidenceRef> mutable = new ArrayList<>(List.of(first, second));

        AiContextReceipt receipt = AiContextReceipt.create(
                "request-1", "workspace-1", "ASK_LYRA",
                "openai-compatible", "test-model", now, mutable,
                List.of("rows<=100", "grant-1", "grant-1"),
                List.of("sample-data"));
        mutable.clear();

        AiContextReceipt reordered = AiContextReceipt.create(
                "request-1", "workspace-1", "ASK_LYRA",
                "openai-compatible", "test-model", now,
                List.of(second, first), List.of("grant-1", "rows<=100"),
                List.of("sample-data"));

        assertEquals(2, receipt.evidence().size());
        assertEquals(receipt.contextSha256(), reordered.contextSha256());
        assertThrows(UnsupportedOperationException.class,
                () -> receipt.evidence().add(first));
    }

    @Test
    void forgedDigestIsRejected() {
        Instant now = Instant.parse("2026-08-03T04:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> new AiContextReceipt(
                        "request-1", "workspace-1", "ASK_LYRA",
                        null, null, now, List.of(), List.of(), List.of(),
                        "0".repeat(64)));
    }

    private static EvidenceRef evidence(String id, Instant now) {
        return new EvidenceRef(id, AiEvidenceType.METADATA_SNAPSHOT,
                "元数据 " + id, "metadata:" + id,
                AiDigest.sha256(id), now, EvidenceTrustLevel.OBSERVED);
    }
}
