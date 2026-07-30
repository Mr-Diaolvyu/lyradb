package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataSnapshotSessionStoreTest {

    private final MetadataSnapshotSessionStore store =
            new MetadataSnapshotSessionStore();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        store.clear();
    }

    @Test
    void wrongOwnerCannotConsumeAndLegitimateReplayIsRejected() {
        MetadataSnapshotSessionStore.SnapshotSession session =
                createSession();

        assertThrows(RuntimeException.class, () -> store.consumeForAi(
                session.id(), "owner-2", "workspace-1"));

        store.consumeForAi(
                session.id(), "owner-1", "workspace-1");
        assertThrows(RuntimeException.class, () -> store.consumeForAi(
                session.id(), "owner-1", "workspace-1"));
    }

    @Test
    void concurrentAiAttachmentsCanSucceedOnlyOnce() throws Exception {
        MetadataSnapshotSessionStore.SnapshotSession session =
                createSession();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    store.consumeForAi(
                            session.id(), "owner-1", "workspace-1");
                    return true;
                } catch (RuntimeException exception) {
                    return false;
                }
            }));
        }
        ready.await();
        start.countDown();

        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        assertEquals(1, successes);
    }

    @Test
    void perOwnerWorkspaceLimitCannotBeExceeded() {
        for (int index = 0; index < 10; index++) {
            createSession();
        }
        assertThrows(IllegalStateException.class,
                this::createSession);
    }

    @Test
    void globalByteBudgetIsReleasedByDiscardAndClear() {
        MetadataSnapshot snapshot = testSnapshot();
        long bytes = new MetadataSnapshotRenderer()
                .toJsonUtf8(snapshot).length;
        MetadataSnapshotSessionStore limited =
                new MetadataSnapshotSessionStore(bytes);

        MetadataSnapshotSessionStore.SnapshotSession first =
                createSession(limited, "owner-a", snapshot);
        assertThrows(IllegalStateException.class,
                () -> createSession(limited, "owner-b", snapshot));

        limited.discard(first.id(), "owner-a", "workspace-1");
        createSession(limited, "owner-b", snapshot);
        limited.clear();
        createSession(limited, "owner-c", snapshot);
        limited.clear();
    }

    @Test
    void expiredSnapshotReleasesGlobalByteBudget() {
        MetadataSnapshot snapshot = testSnapshot();
        long bytes = new MetadataSnapshotRenderer()
                .toJsonUtf8(snapshot).length;
        AtomicReference<LocalDateTime> now = new AtomicReference<>(
                LocalDateTime.of(2026, 7, 30, 12, 0));
        MetadataSnapshotSessionStore limited =
                new MetadataSnapshotSessionStore(bytes, now::get);
        MetadataSnapshotSessionStore.SnapshotSession expired =
                createSession(limited, "owner-a", snapshot);

        now.set(now.get().plusMinutes(
                MetadataSnapshotSessionStore.TTL_MINUTES + 1L));
        createSession(limited, "owner-b", snapshot);

        assertThrows(RuntimeException.class, () -> limited.require(
                expired.id(), "owner-a", "workspace-1"));
        limited.clear();
    }

    @Test
    void concurrentCreatesReserveGlobalByteBudgetAtomically()
            throws Exception {
        MetadataSnapshot snapshot = testSnapshot();
        long bytes = new MetadataSnapshotRenderer()
                .toJsonUtf8(snapshot).length;
        MetadataSnapshotSessionStore limited =
                new MetadataSnapshotSessionStore(bytes);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String ownerId = "owner-" + index;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    createSession(limited, ownerId, snapshot);
                    return true;
                } catch (IllegalStateException exception) {
                    return false;
                }
            }));
        }
        ready.await();
        start.countDown();

        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        assertEquals(1, successes);
        limited.clear();
    }

    private static MetadataSnapshotSessionStore.SnapshotSession createSession(
            MetadataSnapshotSessionStore target, String ownerId,
            MetadataSnapshot snapshot) {
        return target.create(
                ownerId, "workspace-1", "grant-1", "source-1",
                "sales-source", "fingerprint",
                new MetadataSnapshotSessionStore.MapScope(
                        null, List.of("PUBLIC"), List.of()),
                snapshot, 0, 0, 10);
    }

    private static MetadataSnapshot testSnapshot() {
        return MetadataSnapshot.of(List.of(
                new MetadataSnapshot.DataSource(
                        "source-1", "source", "H2", "", List.of())));
    }
    private MetadataSnapshotSessionStore.SnapshotSession createSession() {
        MetadataSnapshot snapshot = MetadataSnapshot.of(List.of(
                new MetadataSnapshot.DataSource(
                        "source-1", "source", "H2", "", List.of())));
        return store.create(
                "owner-1", "workspace-1", "grant-1", "source-1",
                "sales-source", "fingerprint",
                new MetadataSnapshotSessionStore.MapScope(
                        null, List.of("PUBLIC"), List.of()),
                snapshot, 0, 0, 10);
    }
}
