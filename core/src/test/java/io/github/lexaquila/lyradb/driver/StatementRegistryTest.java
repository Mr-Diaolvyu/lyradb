package io.github.lexaquila.lyradb.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StatementRegistryTest {

    private final Object firstConnection = new Object();
    private final Object secondConnection = new Object();

    @AfterEach
    void cleanup() {
        StatementRegistry.unregister(firstConnection);
        StatementRegistry.unregister(secondConnection);
        StatementRegistry.end();
    }

    @Test
    void remembersCancellationWhileWaitingForStatement() throws Exception {
        String id = "queued";
        Statement statement = mock(Statement.class);
        StatementRegistry.prepare(id);
        try {
            assertTrue(StatementRegistry.requestCancellation(id));
            StatementRegistry.begin(id);
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> StatementRegistry.register(firstConnection, statement));
            verify(statement, never()).execute(anyString());
        } finally { StatementRegistry.release(id); }
    }

    @Test
    void cancelByExecutionIdNeverCancelsAnotherRequest() throws Exception {
        Statement first = mock(Statement.class);
        Statement second = mock(Statement.class);

        StatementRegistry.begin("task-a");
        StatementRegistry.register(firstConnection, first);
        StatementRegistry.end();

        StatementRegistry.begin("task-b");
        StatementRegistry.register(secondConnection, second);
        StatementRegistry.end();

        assertTrue(StatementRegistry.cancelExecution("task-a"));
        verify(first).cancel();
        verify(second, never()).cancel();
    }
}
