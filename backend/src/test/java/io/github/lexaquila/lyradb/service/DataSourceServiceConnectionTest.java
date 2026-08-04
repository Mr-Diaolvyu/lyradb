package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
