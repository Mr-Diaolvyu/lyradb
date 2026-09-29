package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class DataSourceServiceConnectionTest {

    private DataSourceRepository repository;
    private DriverFactory driverFactory;
    private CredentialService credentialService;
    private DatabaseDriver driver;
    private DataSourceService service;

    @BeforeEach
    void setUp() {
        repository = mock(DataSourceRepository.class);
        driverFactory = mock(DriverFactory.class);
        credentialService = mock(CredentialService.class);
        driver = mock(DatabaseDriver.class);
        service = new DataSourceService(
                repository, driverFactory, mock(DriverRegistry.class),
                credentialService,
                mock(ApprovalSecurityContextService.class),
                new ObjectMapper());

        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("MYSQL");
        source.setDisplayName("测试数据源");
        source.setConnectionParamsJson("{}");
        when(repository.findById("source-1"))
                .thenReturn(Optional.of(source));
        when(credentialService.decryptSensitiveFields(anyMap()))
                .thenReturn(Map.of("host", "127.0.0.1"));
        when(driverFactory.createDriver("MYSQL")).thenReturn(driver);
        when(driverFactory.getOrCreateDriver("source-1", "MYSQL"))
                .thenReturn(driver);
    }

    @Test
    void successfulTestReturnsElapsedTimeAndClosesConnection()
            throws Exception {
        Object connection = new Object();
        when(driver.connect(anyMap())).thenReturn(connection);

        Map<String, Object> result = service.test("source-1");

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals("连接成功", result.get("message"));
        Number elapsed = assertInstanceOf(
                Number.class, result.get("elapsedMs"));
        assertTrue(elapsed.longValue() >= 0L);
        assertEquals("CONNECTED", result.get("status"));
        verify(repository).recordTest(eq("source-1"), eq("CONNECTED"),
                any(LocalDateTime.class), any(Long.class), eq(null), any(String.class));
        verify(driver).disconnect(connection);
    }

    @Test
    void failedTestReturnsVisibleReasonAndDoesNotDisconnectNullConnection()
            throws Exception {
        when(driver.connect(anyMap()))
                .thenThrow(new IllegalStateException("connect timed out"));

        Map<String, Object> result = service.test("source-1");

        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals("连接超时，请检查网络、VPN、防火墙和访问白名单",
                result.get("message"));
        assertInstanceOf(Number.class, result.get("elapsedMs"));
        verify(driver, never()).disconnect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unavailableDriverIsDistinguishedFromFailedConnection() {
        when(driverFactory.createDriver("MYSQL"))
                .thenThrow(new IllegalStateException("driver unavailable"));

        Map<String, Object> result = service.test("source-1");

        assertEquals("DRIVER_UNAVAILABLE", result.get("status"));
    }

    @Test
    void changedConfigurationInvalidatesPreviousConnectedMarker() {
        DataSource source = repository.findById("source-1").orElseThrow();
        source.setLastTestStatus("CONNECTED");
        source.setLastTestedAt(LocalDateTime.now());
        source.setLastTestConfigHash("old-config-hash");

        assertEquals("STALE", service.getMasked("source-1").get("lastTestStatus"));
    }

    @Test
    void disconnectFailureDoesNotHideSuccessfulConnectivityResult()
            throws Exception {
        Object connection = new Object();
        when(driver.connect(anyMap())).thenReturn(connection);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed"))
                .when(driver).disconnect(connection);

        Map<String, Object> result = service.test("source-1");

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals("连接成功", result.get("message"));
        assertInstanceOf(Number.class, result.get("elapsedMs"));
        verify(driver).disconnect(connection);
    }

    @Test
    void healthyCachedJdbcConnectionIsReused() throws Exception {
        Connection connection = mock(Connection.class);
        when(driver.connect(anyMap())).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);

        var first = service.resolveActiveConnection("source-1");
        var second = service.resolveActiveConnection("source-1");

        assertSame(first, second);
        verify(driver, times(1)).connect(anyMap());
        verify(driver, never()).disconnect(connection);
    }

    @Test
    void closedCachedJdbcConnectionIsReplaced() throws Exception {
        Connection closed = mock(Connection.class);
        Connection fresh = mock(Connection.class);
        when(driver.connect(anyMap())).thenReturn(closed, fresh);
        when(closed.isValid(3)).thenReturn(false);

        var first = service.resolveActiveConnection("source-1");
        var second = service.resolveActiveConnection("source-1");

        assertSame(closed, first.connection);
        assertSame(fresh, second.connection);
        verify(driver).disconnect(closed);
        verify(driver, times(2)).connect(anyMap());
    }

    @Test
    void jdbcValidationFailureAlsoReplacesCachedConnection() throws Exception {
        Connection closed = mock(Connection.class);
        Connection fresh = mock(Connection.class);
        when(driver.connect(anyMap())).thenReturn(closed, fresh);
        when(closed.isValid(3)).thenThrow(new SQLException("connection closed"));

        service.resolveActiveConnection("source-1");
        var replacement = service.resolveActiveConnection("source-1");

        assertSame(fresh, replacement.connection);
        verify(driver).disconnect(closed);
    }

    @Test
    void queryConnectionsBypassEvenHealthyCacheAndReadCurrentConfiguration() throws Exception {
        Connection cached = mock(Connection.class);
        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        when(driver.connect(anyMap())).thenReturn(cached, first, second);
        when(cached.isValid(3)).thenReturn(true);
        service.resolveActiveConnection("source-1");

        try (var query = service.openQueryConnection("source-1")) {
            assertSame(first, query.connection);
            assertNotSame(cached, query.connection);
        }
        DataSource source = repository.findById("source-1").orElseThrow();
        source.setConnectionParamsJson("{\"host\":\"new-host\"}");
        when(credentialService.decryptSensitiveFields(Map.of("host", "new-host")))
                .thenReturn(Map.of("host", "new-host"));
        try (var query = service.openQueryConnection("source-1")) {
            assertSame(second, query.connection);
        }

        verify(driver).connect(Map.of("host", "new-host"));
        verify(driver).disconnect(first);
        verify(driver).disconnect(second);
        verify(driver, never()).disconnect(cached);
        verify(driverFactory, times(2)).createDriver("MYSQL");
    }

    @Test
    void queryCloseIsIdempotentAndRejectsStaleReferences() throws Exception {
        Object connection = new Object();
        when(driver.connect(anyMap())).thenReturn(connection);
        var query = service.openQueryConnection("source-1");

        query.close();
        query.close();

        verify(driver, times(1)).disconnect(connection);
        assertThrows(IllegalStateException.class, query::acquire);
    }

    @Test
    void queryConnectionFailureDoesNotFallBackToCacheOrRetry() throws Exception {
        when(driver.connect(anyMap())).thenThrow(new SQLException("connect failed"));

        assertThrows(IllegalStateException.class,
                () -> service.openQueryConnection("source-1"));

        verify(driver, times(1)).connect(anyMap());
        verify(driver, never()).disconnect(any());
        verify(driverFactory, never()).getOrCreateDriver(any(), any());
    }

    @Test
    void queryRejectsNullConnection() throws Exception {
        when(driver.connect(anyMap())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.openQueryConnection("source-1"));
        verify(driver, never()).disconnect(any());
    }

    @Test
    void queryCleanupFailurePreservesOriginalException() throws Exception {
        Object connection = new Object();
        when(driver.connect(anyMap())).thenReturn(connection);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed"))
                .when(driver).disconnect(connection);
        SQLException original = new SQLException("query failed");

        SQLException actual = assertThrows(SQLException.class, () -> {
            try (var query = service.openQueryConnection("source-1")) {
                throw original;
            }
        });

        assertSame(original, actual);
        verify(driver).disconnect(connection);
    }
}
