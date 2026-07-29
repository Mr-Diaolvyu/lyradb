package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ActiveConnectionConcurrencyTest {

    @Test
    void leaseSerializesPhysicalConnectionAccess() throws Exception {
        ConnectionService.ActiveConnection active = new ConnectionService.ActiveConnection(
                mock(DatabaseDriver.class), new Object());
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Runnable work = () -> {
                try {
                    start.await();
                    try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
                        maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                        Thread.sleep(50);
                        concurrent.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, maximum.get());
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void closedConnectionRejectsStaleReferences() {
        ConnectionService.ActiveConnection active = new ConnectionService.ActiveConnection(
                mock(DatabaseDriver.class), new Object());

        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            active.markClosed();
        }

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, active::acquire);
        assertEquals("数据库连接已断开，请重新连接", exception.getMessage());
    }

}
