package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageEntry;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageRisk;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataSourceImportPreviewStoreTest {

    private final DataSourceImportPreviewStore store =
            new DataSourceImportPreviewStore();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        store.clear();
    }

    @Test
    void wrongWorkspaceDoesNotConsumeTokenAndReplayIsRejected() {
        DataSourceImportPreviewStore.PreviewSession session =
                createSession();

        assertThrows(RuntimeException.class, () -> store.consume(
                session.token(), "owner-1", "workspace-2"));

        assertEquals(session, store.consume(
                session.token(), "owner-1", "workspace-1"));
        assertThrows(RuntimeException.class, () -> store.consume(
                session.token(), "owner-1", "workspace-1"));
    }

    @Test
    void concurrentConsumersCanSucceedOnlyOnce() throws Exception {
        DataSourceImportPreviewStore.PreviewSession session =
                createSession();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    store.consume(
                            session.token(), "owner-1", "workspace-1");
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

    private DataSourceImportPreviewStore.PreviewSession createSession() {
        ConnectionPackageEntry entry =
                ConnectionPackageEntry.fromMixedParameters(
                        "", "source", "H2", Map.of("url", "jdbc:h2:mem:test"),
                        Set.of(), "", false);
        return store.create(
                "owner-1", "workspace-1", CredentialExportPolicy.OMIT,
                ConnectionPackageRisk.CREDENTIALS_OMITTED, List.of(entry));
    }
}
