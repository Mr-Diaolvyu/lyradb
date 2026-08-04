package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.AiMaxComputePreflight;
import io.github.lexaquila.lyradb.repository.AiMaxComputePreflightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaxComputePreflightStoreTest {

    @Mock
    private AiMaxComputePreflightRepository repository;
    private AtomicReference<AiMaxComputePreflight> persisted;
    private MaxComputePreflightStore store;

    @BeforeEach
    void setUp() {
        persisted = new AtomicReference<>();
        store = new MaxComputePreflightStore(repository);
        when(repository.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    AiMaxComputePreflight value = invocation.getArgument(0);
                    persisted.set(value);
                    return value;
                });
        when(repository.findByTokenForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    AiMaxComputePreflight value = persisted.get();
                    return value != null && value.getTokenSha256().equals(
                            invocation.getArgument(0))
                            ? Optional.of(value) : Optional.empty();
                });
        doAnswer(invocation -> {
            persisted.compareAndSet(invocation.getArgument(0), null);
            return null;
        }).when(repository).delete(any());
    }

    @Test
    void preflightTokenIsBoundAndSingleUseAcrossStoreInstances() {
        var session = store.issue(
                "workspace-1", "user-1", "grant-1",
                "a".repeat(64), 8L, Instant.now().plusSeconds(60));

        new MaxComputePreflightStore(repository).requireAndConsume(
                session.tokenSha256(), "workspace-1", "user-1",
                "grant-1", "a".repeat(64), 8L);

        assertThrows(IllegalArgumentException.class,
                () -> store.requireAndConsume(
                        session.tokenSha256(), "workspace-1", "user-1",
                        "grant-1", "a".repeat(64), 8L));
    }
}
