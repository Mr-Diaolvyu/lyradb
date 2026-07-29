package io.github.lexaquila.lyradb.desktop.db;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.desktop.storage.DesktopStateStore;
import io.github.lexaquila.lyradb.desktop.storage.DesktopVault;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.GenericJdbcDriver;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.FormField;
import io.github.lexaquila.lyradb.service.SqlReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class NativeConnectionManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldExecuteQueriesBlockDangerousSqlAndManageTransaction() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setMaxQueryRows(100);
        properties.setQueryTimeoutSeconds(5);
        DatabaseDriver driver = h2Driver();

        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            DesktopStateStore store = new DesktopStateStore(tempDirectory, vault);
            DesktopConnection connection = new DesktopConnection();
            connection.setName("H2 测试");
            connection.setDbType("H2");
            connection.setParams(Map.of("database", "lyradb_native_test"));
            connection = store.saveConnection(connection);

            try (NativeConnectionManager manager = new NativeConnectionManager(
                    new FixedDriverFactory(driver), store,
                    new SqlReviewService(), properties)) {
                manager.connect(connection.getId());
                assertThat(manager.isConnected(connection.getId())).isTrue();

                manager.execute(connection.getId(),
                        "CREATE TABLE account(id INT PRIMARY KEY, name VARCHAR(50))",
                        100, false, "create");
                var namespaces = manager.tree(connection.getId(), null);
                assertThat(namespaces).anyMatch(
                        node -> "PUBLIC".equalsIgnoreCase(node.getName()));
                var tables = manager.tree(connection.getId(), "PUBLIC");
                assertThat(tables).anyMatch(
                        node -> "ACCOUNT".equalsIgnoreCase(node.getName()));
                assertThat(manager.columns(connection.getId(), "PUBLIC", "ACCOUNT"))
                        .anyMatch(column -> "ID".equalsIgnoreCase(column.getName())
                                && column.isPrimaryKey());
                manager.execute(connection.getId(),
                        "INSERT INTO account VALUES (1, 'Alice')",
                        100, false, "insert");

                var query = manager.execute(connection.getId(),
                        "SELECT id, name FROM account ORDER BY id",
                        100, false, "query");
                assertThat(query.queryResult().getRows()).hasSize(1);
                assertThat(query.queryResult().getRows().get(0).get("NAME"))
                        .isEqualTo("Alice");

                var blocked = manager.execute(connection.getId(),
                        "DELETE FROM account", 100, false, "blocked");
                assertThat(blocked.blocked()).isTrue();
                assertThat(blocked.findings()).anyMatch(
                        finding -> "R2_DELETE_NO_WHERE".equals(finding.getRuleId()));

                manager.beginTransaction(connection.getId());
                manager.execute(connection.getId(),
                        "INSERT INTO account VALUES (2, 'Bob')",
                        100, false, "transaction-insert");
                manager.rollback(connection.getId());
                var afterRollback = manager.execute(connection.getId(),
                        "SELECT COUNT(*) AS CNT FROM account",
                        100, false, "count");
                assertThat(afterRollback.queryResult().getRows().get(0).get("CNT"))
                        .isEqualTo(1L);
            }
        }
    }

    @Test
    void shouldSerializeLockedJdbcMetadataWithQueryExecution()
            throws Exception {
        AppProperties properties = new AppProperties();
        properties.setMaxQueryRows(100);
        DatabaseDriver driver = h2Driver();

        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            DesktopStateStore store = new DesktopStateStore(tempDirectory, vault);
            DesktopConnection connection = new DesktopConnection();
            connection.setName("H2 锁测试");
            connection.setDbType("H2");
            connection.setParams(Map.of("database", "lyradb_lock_test"));
            connection = store.saveConnection(connection);

            try (NativeConnectionManager manager = new NativeConnectionManager(
                    new FixedDriverFactory(driver), store,
                    new SqlReviewService(), properties)) {
                manager.connect(connection.getId());
                manager.execute(connection.getId(),
                        "CREATE TABLE lock_probe(id INT PRIMARY KEY)",
                        100, false, "create-lock-probe");

                CountDownLatch lockEntered = new CountDownLatch(1);
                CountDownLatch releaseLock = new CountDownLatch(1);
                CountDownLatch queryStarted = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(2);
                Future<Void> lockHolder = null;
                Future<NativeConnectionManager.ExecutionResult> query = null;
                try {
                    String connectionId = connection.getId();
                    lockHolder = executor.submit(() ->
                            manager.withLockedJdbcConnection(
                                    connectionId, jdbc -> {
                                        lockEntered.countDown();
                                        if (!releaseLock.await(
                                                5, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "等待释放测试锁超时");
                                        }
                                        return null;
                                    }));
                    assertThat(lockEntered.await(5, TimeUnit.SECONDS)).isTrue();

                    query = executor.submit(() -> {
                        queryStarted.countDown();
                        return manager.execute(connectionId,
                                "SELECT COUNT(*) AS CNT FROM lock_probe",
                                100, false, "query-during-metadata");
                    });
                    assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    Future<NativeConnectionManager.ExecutionResult> waitingQuery =
                            query;
                    assertThatThrownBy(() ->
                            waitingQuery.get(200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);

                    releaseLock.countDown();
                    lockHolder.get(5, TimeUnit.SECONDS);
                    assertThat(query.get(5, TimeUnit.SECONDS).queryResult())
                            .isNotNull();
                } finally {
                    releaseLock.countDown();
                    if (lockHolder != null) {
                        lockHolder.cancel(true);
                    }
                    if (query != null) {
                        query.cancel(true);
                    }
                    executor.shutdownNow();
                }
            }
        }
    }

    private static DatabaseDriver h2Driver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("H2");
        info.setDisplayName("H2 Test");
        info.setDriverType("jdbc");
        info.setDriverClass("org.h2.Driver");
        info.setConnectionUrlTemplate("jdbc:h2:mem:{database};DB_CLOSE_DELAY=-1");
        DriverCapability capabilities = new DriverCapability();
        capabilities.setSupportsTransaction(true);
        capabilities.setSupportsDML(true);
        capabilities.setSupportsDDL(true);
        capabilities.setSupportsViews(true);
        info.setCapabilities(capabilities);
        FormField database = new FormField();
        database.setName("database");
        database.setLabel("数据库");
        database.setType("text");
        database.setRequired(true);
        info.setConnectionFormFields(List.of(database));
        return new GenericJdbcDriver(info,
                NativeConnectionManagerTest.class.getClassLoader());
    }

    private static final class FixedDriverFactory extends DriverFactory {
        private final DatabaseDriver driver;

        private FixedDriverFactory(DatabaseDriver driver) {
            super(null, null, new AppProperties());
            this.driver = driver;
        }

        @Override
        public DatabaseDriver createDriver(String dbType) {
            return driver;
        }

        @Override
        public DatabaseDriver getOrCreateDriver(String connectionId, String dbType) {
            return driver;
        }

        @Override
        public void removeDriver(String connectionId) {
            // 测试驱动无需缓存。
        }
    }
}
