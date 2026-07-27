package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 查询执行服务单元测试
 */
class QueryServiceTest {

    private ConnectionService connectionService;
    private AppProperties appProperties;
    private QueryHistoryService queryHistoryService;
    private SqlReviewService sqlReviewService;
    private QueryService queryService;

    @BeforeEach
    void setUp() {
        connectionService = mock(ConnectionService.class);
        appProperties = new AppProperties();
        appProperties.setMaxQueryRows(100);
        queryHistoryService = mock(QueryHistoryService.class);
        // 审核服务默认无命中（空 findings，不拦截）
        sqlReviewService = mock(SqlReviewService.class);
        when(sqlReviewService.review(any(), any())).thenReturn(java.util.List.of());
        queryService = new QueryService(connectionService, appProperties, queryHistoryService, sqlReviewService);
    }

    private ConnectionService.ActiveConnection activeConnection(boolean readOnly) {
        DriverCapability cap = new DriverCapability();
        cap.setReadOnly(readOnly);
        DatabaseDriver driver = mock(DatabaseDriver.class);
        when(driver.getCapabilities()).thenReturn(cap);
        DriverInfo info = new DriverInfo();
        info.setDbType(readOnly ? "MAXCOMPUTE" : "MYSQL");
        when(driver.getDriverInfo()).thenReturn(info);
        return new ConnectionService.ActiveConnection(driver, new Object());
    }

    @Test
    void executeUpdateRejectsReadOnlyOlap() {
        // 先构建 active connection（内部含 Mockito stub，不能在 when(...).thenReturn(...) 参数中内联调用）
        ConnectionService.ActiveConnection active = activeConnection(true);
        when(connectionService.getActiveConnection("c1")).thenReturn(active);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> queryService.executeUpdate("c1", "INSERT INTO t VALUES (1)", null));
        assertTrue(ex.getMessage().contains("只读"));
        // 只读拦截在前，不应记录历史
        verify(queryHistoryService, never())
                .record(any(), any(), any(), anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    void executeQueryLimitPassthrough() throws Exception {
        DatabaseDriver mockDriver = mock(DatabaseDriver.class);
        when(mockDriver.getCapabilities()).thenReturn(new DriverCapability());
        DriverInfo info = new DriverInfo();
        info.setDbType("MYSQL");
        when(mockDriver.getDriverInfo()).thenReturn(info);
        io.github.lexaquila.lyradb.model.dto.QueryResult sample = new io.github.lexaquila.lyradb.model.dto.QueryResult();
        sample.addColumn("id");
        sample.setTotalRows(0);
        when(mockDriver.executeQuery(any(), eq("SELECT 1"), eq(100))).thenReturn(sample);

        when(connectionService.getActiveConnection("c1"))
                .thenReturn(new ConnectionService.ActiveConnection(mockDriver, new Object()));

        io.github.lexaquila.lyradb.model.dto.QueryResult result = queryService.executeQuery("c1", "SELECT 1", null);
        assertNotNull(result);
        verify(mockDriver).executeQuery(any(), eq("SELECT 1"), eq(100));
    }

    @Test
    void executeQueryRecordsHistoryOnSuccess() throws Exception {
        DatabaseDriver mockDriver = mock(DatabaseDriver.class);
        when(mockDriver.getCapabilities()).thenReturn(new DriverCapability());
        DriverInfo info = new DriverInfo();
        info.setDbType("MYSQL");
        when(mockDriver.getDriverInfo()).thenReturn(info);
        io.github.lexaquila.lyradb.model.dto.QueryResult sample = new io.github.lexaquila.lyradb.model.dto.QueryResult();
        sample.addColumn("id");
        sample.setElapsedMs(5);
        sample.setTotalRows(1);
        when(mockDriver.executeQuery(any(), eq("SELECT 1"), eq(100))).thenReturn(sample);

        when(connectionService.getActiveConnection("c1"))
                .thenReturn(new ConnectionService.ActiveConnection(mockDriver, new Object()));

        queryService.executeQuery("c1", "SELECT 1", null);
        verify(queryHistoryService).record(eq("c1"), eq("MYSQL"), eq("SELECT 1"),
                eq(5L), eq(1L), eq(true), isNull());
    }
}
