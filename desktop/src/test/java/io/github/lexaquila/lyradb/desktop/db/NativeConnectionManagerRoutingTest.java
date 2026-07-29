package io.github.lexaquila.lyradb.desktop.db;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.desktop.storage.DesktopStateStore;
import io.github.lexaquila.lyradb.desktop.storage.DesktopVault;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.service.SqlReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeConnectionManagerRoutingTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldRouteRedisAndMongoCommandsByTheirNativeSemantics() throws Exception {
        DatabaseDriver redis = mockDriver();
        DatabaseDriver mongo = mockDriver();
        Object redisConnection = new Object();
        Object mongoConnection = new Object();
        when(redis.connect(any())).thenReturn(redisConnection);
        when(mongo.connect(any())).thenReturn(mongoConnection);

        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            DesktopStateStore store = new DesktopStateStore(tempDirectory, vault);
            DesktopConnection redisDefinition = saveConnection(store, "REDIS");
            DesktopConnection mongoDefinition = saveConnection(store, "MONGODB");
            AppProperties properties = new AppProperties();
            properties.setMaxQueryRows(100);

            try (NativeConnectionManager manager = new NativeConnectionManager(
                    new MapDriverFactory(Map.of("REDIS", redis, "MONGODB", mongo)),
                    store, new SqlReviewService(), properties)) {
                manager.connect(redisDefinition.getId());
                manager.connect(mongoDefinition.getId());

                manager.execute(redisDefinition.getId(),
                        "GET customer:1", 50, false, "redis-get");
                manager.execute(redisDefinition.getId(),
                        "SET customer:1 Alice", 50, false, "redis-set");
                NativeConnectionManager.ExecutionResult blocked =
                        manager.execute(redisDefinition.getId(),
                                "FLUSHDB", 50, false, "redis-flush-blocked");
                manager.execute(redisDefinition.getId(),
                        "FLUSHDB", 50, true, "redis-flush-forced");

                manager.execute(mongoDefinition.getId(),
                        "app.customer", 50, false, "mongo-find");
                String mongoUpdate = "{\"op\":\"update\",\"db\":\"app\","
                        + "\"collection\":\"customer\",\"filter\":{\"id\":1},"
                        + "\"update\":{\"$set\":{\"name\":\"Alice\"}}}";
                manager.execute(mongoDefinition.getId(),
                        mongoUpdate, 50, false, "mongo-update");

                assertThat(blocked.blocked()).isTrue();
                assertThat(blocked.findings()).anyMatch(
                        finding -> "R8_REDIS_FLUSH".equals(finding.getRuleId()));
                verify(redis).executeQuery(redisConnection, "GET customer:1", 50);
                verify(redis).executeUpdate(redisConnection, "SET customer:1 Alice");
                verify(redis).executeUpdate(redisConnection, "FLUSHDB");
                verify(mongo).executeQuery(mongoConnection, "app.customer", 50);
                verify(mongo).executeUpdate(mongoConnection, mongoUpdate);
            }
        }

        verify(redis, never()).executeQuery(
                redisConnection, "SET customer:1 Alice", 50);
    }

    private static DatabaseDriver mockDriver() throws Exception {
        DatabaseDriver driver = mock(DatabaseDriver.class);
        DriverCapability capabilities = new DriverCapability();
        capabilities.setReadOnly(false);
        when(driver.getCapabilities()).thenReturn(capabilities);
        when(driver.executeQuery(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> new QueryResult());
        when(driver.executeUpdate(any(), anyString())).thenReturn(1);
        return driver;
    }

    private static DesktopConnection saveConnection(
            DesktopStateStore store, String dbType) {
        DesktopConnection definition = new DesktopConnection();
        definition.setName(dbType + " 测试");
        definition.setDbType(dbType);
        definition.setParams(Map.of());
        return store.saveConnection(definition);
    }

    private static final class MapDriverFactory extends DriverFactory {
        private final Map<String, DatabaseDriver> drivers;

        private MapDriverFactory(Map<String, DatabaseDriver> drivers) {
            super(null, null, new AppProperties());
            this.drivers = drivers;
        }

        @Override
        public DatabaseDriver createDriver(String dbType) {
            return require(dbType);
        }

        @Override
        public DatabaseDriver getOrCreateDriver(String connectionId, String dbType) {
            return require(dbType);
        }

        @Override
        public void removeDriver(String connectionId) {
            // 测试驱动无需缓存。
        }

        private DatabaseDriver require(String dbType) {
            DatabaseDriver driver = drivers.get(dbType);
            if (driver == null) {
                throw new IllegalArgumentException("未配置测试驱动: " + dbType);
            }
            return driver;
        }
    }
}
