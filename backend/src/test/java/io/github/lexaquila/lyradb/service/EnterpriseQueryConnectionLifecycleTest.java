package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实服务链验证连接归属与资源释放，不访问外部数据库。 */
class EnterpriseQueryConnectionLifecycleTest {
    private static final String SQL = "select id from dw.orders";
    private final List<Connection> opened = new CopyOnWriteArrayList<>();
    private DriverFactory factory;
    private DatabaseDriver driver;
    private DataSourceService sources;
    private EnterpriseQueryService queries;
    private MaskingService masking;
    private Grant grant;

    @BeforeEach
    void setUp() throws Exception {
        DataSourceRepository repository = mock(DataSourceRepository.class);
        CredentialService credentials = mock(CredentialService.class);
        factory = mock(DriverFactory.class);
        driver = mock(DatabaseDriver.class);
        sources = spy(new DataSourceService(repository, factory,
                mock(DriverRegistry.class), credentials,
                mock(ApprovalSecurityContextService.class), new ObjectMapper()));
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("MYSQL");
        source.setConnectionParamsJson("{}");
        when(repository.findById("source-1")).thenReturn(Optional.of(source));
        when(credentials.decryptSensitiveFields(anyMap())).thenReturn(Map.of());
        when(factory.createDriver("MYSQL")).thenReturn(driver);
        when(driver.connect(anyMap())).thenAnswer(invocation -> {
            Connection jdbc = mock(Connection.class);
            when(jdbc.isReadOnly()).thenReturn(true);
            opened.add(jdbc);
            return jdbc;
        });
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof Connection jdbc) {
                jdbc.close();
            }
            return null;
        }).when(driver).disconnect(any());
        when(driver.executeQuery(any(), eq(SQL), eq(100))).thenAnswer(invocation -> result());

        User user = new User();
        user.setId("user-1");
        user.setUsername("tester");
        grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setDataSourceId("source-1");
        grant.setUserId("user-1");
        grant.setGrantedSourceName("sales");
        grant.setAllowedSchemas("dw");
        grant.setAllowedTables("dw.orders");
        grant.setMaxRowsPerQuery(100);
        GrantService grants = mock(GrantService.class);
        when(grants.resolveForUser("user-1", "workspace-1", "sales")).thenReturn(grant);
        SecurityUtil security = mock(SecurityUtil.class);
        when(security.requireCurrentUser()).thenReturn(user);
        when(security.requireCurrentWorkspace()).thenReturn("workspace-1");
        when(security.effectiveRoles("workspace-1")).thenReturn(Set.of("ANALYST"));
        SqlReviewService review = mock(SqlReviewService.class);
        when(review.review(anyString(), eq("MYSQL"))).thenReturn(List.of());
        masking = mock(MaskingService.class);
        queries = new EnterpriseQueryService(grants, sources, mock(AuditService.class),
                security, review, mock(ApprovalService.class), masking, new AppProperties());
    }

    @Test
    void consecutiveQueriesOpenAndCloseDifferentPhysicalConnections() throws Exception {
        assertEquals(List.of("id"), queries.executeQuery("sales", SQL, null).getColumns());
        verify(opened.get(0)).close();
        queries.executeQuery("sales", SQL, null);

        assertEquals(2, opened.size());
        assertNotSame(opened.get(0), opened.get(1));
        verify(opened.get(1)).close();
        verify(factory, times(2)).createDriver("MYSQL");
        verify(factory, never()).getOrCreateDriver(anyString(), anyString());
        verify(sources, never()).resolveActiveConnection(anyString());
        verify(sources, never()).disconnect(anyString());
    }

    @Test
    void timeoutClosesConnectionWithoutRetryingSql() throws Exception {
        SQLTimeoutException timeout = new SQLTimeoutException("test timeout");
        when(driver.executeQuery(any(), eq(SQL), eq(100))).thenThrow(timeout);

        assertSame(timeout, assertThrows(SQLTimeoutException.class,
                () -> queries.executeQuery("sales", SQL, null)));

        verify(opened.get(0)).close();
        verify(driver, times(1)).executeQuery(any(), eq(SQL), eq(100));
    }

    @Test
    void interruptedQueryStillClosesItsConnection() throws Exception {
        when(driver.executeQuery(any(), eq(SQL), eq(100))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new SQLException("query cancelled");
        });
        try {
            assertThrows(SQLException.class, () -> queries.executeQuery("sales", SQL, null));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(opened.get(0)).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void namespaceFailureClosesOnlyThisQueryConnectionBeforeExecutingSql() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> queries.executeQuery("sales", SQL, "dw"));

        verify(opened.get(0)).close();
        verify(driver, never()).executeQuery(any(), anyString(), anyInt());
        verify(sources, never()).disconnect(anyString());
    }

    @Test
    void deniedQueryDoesNotOpenAConnection() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> queries.executeQuery("sales", "select id from dw.secrets", null));
        verifyNoInteractions(factory);
    }

    @Test
    void concurrentQueryFinishingDoesNotCloseAnotherQueryConnection() throws Exception {
        CountDownLatch firstExecuting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(driver.executeQuery(any(), eq(SQL), eq(100))).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == opened.get(0)) {
                firstExecuting.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            return result();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> queries.executeQuery("sales", SQL, null));
            assertTrue(firstExecuting.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> queries.executeQuery("sales", SQL, null));
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, opened.size());
            verify(opened.get(1)).close();
            verify(opened.get(0), never()).close();
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            verify(opened.get(0)).close();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void tablePreviewClosesMetadataConnectionBeforeOpeningQueryConnection() throws Exception {
        when(driver.buildTablePreviewSql(any(), eq("dw"), eq("orders"), eq(100)))
                .thenReturn(SQL);
        when(driver.executeQuery(any(), eq(SQL), eq(100))).thenAnswer(invocation -> {
            assertEquals(2, opened.size());
            verify(opened.get(0)).close();
            assertSame(opened.get(1), invocation.getArgument(0));
            return result();
        });

        var inspection = queries.inspectTable("sales", "dw", "orders", "TABLE", 100);

        assertNotNull(inspection.getPreview());
        verify(opened.get(1)).close();
    }

    @Test
    void exportClosesResultSetStatementAndConnectionAfterConsumption() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        Statement statement = prepareExport(rows);
        EnterpriseQueryService.ExportConsumer consumer = mock(EnterpriseQueryService.ExportConsumer.class);
        doAnswer(invocation -> {
            verify(opened.get(0), never()).close();
            return null;
        }).when(consumer).onRow(anyMap());

        var summary = queries.streamExport(grant, SQL, null, consumer);

        assertEquals(1, summary.rowCount());
        var order = inOrder(rows, statement, opened.get(0));
        order.verify(rows).close();
        order.verify(statement).close();
        order.verify(opened.get(0)).close();
    }

    @Test
    void exportConsumerFailureStillClosesAllResources() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        Statement statement = prepareExport(rows);
        EnterpriseQueryService.ExportConsumer consumer = mock(EnterpriseQueryService.ExportConsumer.class);
        IOException failure = new IOException("client disconnected");
        doThrow(failure).when(consumer).onRow(anyMap());

        assertSame(failure, assertThrows(IOException.class,
                () -> queries.streamExport(grant, SQL, null, consumer)));

        verify(rows).close();
        verify(statement).close();
        verify(opened.get(0)).close();
    }

    @Test
    void unsupportedExportClosesNonJdbcConnection() throws Exception {
        Object connection = new Object();
        doReturn(connection).when(driver).connect(anyMap());

        assertThrows(IllegalArgumentException.class,
                () -> queries.streamExport(grant, SQL, null,
                        mock(EnterpriseQueryService.ExportConsumer.class)));

        verify(driver).disconnect(connection);
    }

    private Statement prepareExport(ResultSet rows) throws Exception {
        Statement statement = mock(Statement.class);
        doAnswer(invocation -> {
            Connection jdbc = mock(Connection.class);
            when(jdbc.isReadOnly()).thenReturn(true);
            when(jdbc.createStatement()).thenReturn(statement);
            opened.add(jdbc);
            return jdbc;
        }).when(driver).connect(anyMap());
        when(statement.execute(SQL)).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rows);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(1);
        when(masking.preparePlan(anyString(), anyString(), any(), anyList()))
                .thenReturn(mock(MaskingService.MaskingPlan.class));
        return statement;
    }

    private static QueryResult result() {
        QueryResult result = new QueryResult();
        result.addColumn("id");
        return result;
    }
}
