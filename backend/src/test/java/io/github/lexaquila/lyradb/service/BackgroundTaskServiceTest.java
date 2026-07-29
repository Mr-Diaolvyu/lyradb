package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.BackgroundTask;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundTaskServiceTest {

    @Test
    void taskAndResultAreVisibleOnlyToOwner() throws Exception {
        QueryService queryService = mock(QueryService.class);
        TaskWebSocketHandler webSocket = mock(TaskWebSocketHandler.class);
        QueryResult result = new QueryResult();
        result.addColumn("id");
        result.setTotalRows(0);
        when(queryService.executeQuery(eq("c1"), eq("SELECT 1"), isNull(),
                eq(false), anyString())).thenReturn(result);

        BackgroundTaskService service = new BackgroundTaskService(queryService, webSocket);
        try {
            BackgroundTask task = service.submit("alice", "workspace-a",
                    "c1", "连接", "SELECT 1", null, false);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ("RUNNING".equals(task.getStatus()) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("DONE", task.getStatus());
            assertEquals(1, service.list("alice", "workspace-a").size());
            assertTrue(service.list("bob", "workspace-a").isEmpty());
            assertThrows(AccessDeniedException.class,
                    () -> service.getResult(task.getId(), "bob", "workspace-a"));
            assertNotNull(service.getResult(task.getId(), "alice", "workspace-a"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void blankTaskWorkspaceIsNotATenantWildcard() throws Exception {
        QueryService queryService = mock(QueryService.class);
        TaskWebSocketHandler webSocket = mock(TaskWebSocketHandler.class);
        QueryResult result = new QueryResult();
        when(queryService.executeQuery(eq("c1"), eq("SELECT 1"), isNull(),
                eq(false), anyString())).thenReturn(result);

        BackgroundTaskService service = new BackgroundTaskService(queryService, webSocket);
        try {
            BackgroundTask task = service.submit("alice", null,
                    "c1", "连接", "SELECT 1", null, false);

            assertTrue(service.list("alice", "workspace-a").isEmpty());
            assertThrows(AccessDeniedException.class,
                    () -> service.getResult(task.getId(), "alice", "workspace-a"));
        } finally {
            service.shutdown();
        }
    }


    @Test
    void cancellationCannotBeOverwrittenByInterruptedWorker() throws Exception {
        QueryService queryService = mock(QueryService.class);
        TaskWebSocketHandler webSocket = mock(TaskWebSocketHandler.class);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        when(queryService.executeQuery(eq("c1"), eq("SELECT sleep"), isNull(),
                eq(false), anyString())).thenAnswer(invocation -> {
                    started.countDown();
                    new java.util.concurrent.CountDownLatch(1).await();
                    return new QueryResult();
                });

        BackgroundTaskService service = new BackgroundTaskService(queryService, webSocket);
        try {
            BackgroundTask task = service.submit("alice", "workspace-a",
                    "c1", "连接", "SELECT sleep", null, false);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(service.cancel(task.getId(), "alice", "workspace-a"));
            Thread.sleep(50);
            assertEquals("CANCELLED", task.getStatus());
            assertFalse(task.isResultAvailable());
        } finally {
            service.shutdown();
        }
    }

}
