package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.Grant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 默认数据库/Schema 切换必须按数据库语义执行、读回验证并恢复原属性。
 */
class EnterpriseQueryNamespaceSecurityTest {

    private DataSourceService dataSourceService;
    private EnterpriseQueryService service;

    @BeforeEach
    void setUp() {
        dataSourceService = mock(DataSourceService.class);
        service = new EnterpriseQueryService(
                mock(GrantService.class), dataSourceService,
                mock(AuditService.class), mock(SecurityUtil.class),
                mock(SqlReviewService.class), mock(ApprovalService.class),
                mock(MaskingService.class), new AppProperties());
    }

    @Test
    void postgresqlUsesSchemaAndRestoresSchema() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema())
                .thenReturn("public", "tenant", "public");

        EnterpriseQueryService.ConnectionNamespaceState state =
                service.switchNamespace(
                        connection, "tenant", "POSTGRESQL", "source-1");
        state.restore();

        var order = inOrder(connection);
        order.verify(connection).getSchema();
        order.verify(connection).setSchema("tenant");
        order.verify(connection).getSchema();
        order.verify(connection).setSchema("public");
        order.verify(connection).getSchema();
        verify(connection, never()).setCatalog(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mysqlUsesCatalogAndRestoresCatalog() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getCatalog())
                .thenReturn("main", "tenant", "main");

        EnterpriseQueryService.ConnectionNamespaceState state =
                service.switchNamespace(
                        connection, "tenant", "MYSQL", "source-1");
        state.restore();

        var order = inOrder(connection);
        order.verify(connection).getCatalog();
        order.verify(connection).setCatalog("tenant");
        order.verify(connection).getCatalog();
        order.verify(connection).setCatalog("main");
        order.verify(connection).getCatalog();
        verify(connection, never()).setSchema(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readBackMismatchFailsClosedAndDisconnects() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("public", "public");

        assertThrows(IllegalStateException.class,
                () -> service.switchNamespace(
                        connection, "tenant", "POSTGRESQL", "source-1"));

        verify(dataSourceService).disconnect("source-1");
    }

    @Test
    void malformedConcreteNamespaceIsRejectedBeforeDriverOrDisconnect()
            throws Exception {
        Connection connection = mock(Connection.class);

        assertThrows(IllegalArgumentException.class,
                () -> service.switchNamespace(
                        connection, "dw;drop", "POSTGRESQL", "source-1"));

        verify(dataSourceService, never()).disconnect("source-1");
        verify(connection, never()).setSchema(
                org.mockito.ArgumentMatchers.any());
        verify(connection, never()).setCatalog(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wildcardGrantCannotAuthorizeMalformedDefaultNamespace() {
        Grant grant = new Grant();
        grant.setAllowedSchemas("dw*");
        grant.setAllowedTables("dw.orders");

        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeReadOnly(
                        grant, "select id from dw.orders", "dw/invalid"));

        verify(dataSourceService, never()).disconnect("source-1");
    }

    @Test
    void unsupportedNamespaceSwitchFailsClosedAndDisconnects()
            throws Exception {
        Connection connection = mock(Connection.class);

        assertThrows(IllegalStateException.class,
                () -> service.switchNamespace(
                        connection, "tenant", "SQLITE", "source-1"));

        verify(dataSourceService).disconnect("source-1");
        verify(connection, never()).setCatalog(
                org.mockito.ArgumentMatchers.any());
        verify(connection, never()).setSchema(
                org.mockito.ArgumentMatchers.any());
    }
}
