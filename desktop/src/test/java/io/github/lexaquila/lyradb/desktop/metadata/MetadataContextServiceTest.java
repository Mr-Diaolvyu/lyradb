package io.github.lexaquila.lyradb.desktop.metadata;

import io.github.lexaquila.lyradb.desktop.db.NativeConnectionManager;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetadataContextServiceTest {

    @Test
    void redisMustBeRejectedBeforeAnyMetadataEnumeration() {
        NativeConnectionManager manager = mock(NativeConnectionManager.class);
        MetadataContextService service = service(manager);

        assertThatThrownBy(() -> service.collect(
                selection("REDIS"), () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Redis");
        verifyNoInteractions(manager);
    }

    @Test
    void mongoDatabaseCaptureMustOnlyEnumerateCollectionNames()
            throws Exception {
        NativeConnectionManager manager = mock(NativeConnectionManager.class);
        DesktopConnection connection = new DesktopConnection();
        connection.setId("connection-1");
        connection.setName("Mongo");
        connection.setDbType("MONGODB");
        when(manager.requireSaved("connection-1")).thenReturn(connection);
        when(manager.isConnected("connection-1")).thenReturn(true);
        TreeNode collection = TreeNode.of(
                "sales/orders", "orders", "COLLECTION", "sales/orders");
        collection.setHasChildren(true);
        when(manager.tree("connection-1", "sales"))
                .thenReturn(List.of(collection));

        MetadataSelection selection = new MetadataSelection(
                "connection-1", "MONGODB",
                MetadataSelection.Scope.DATABASE,
                "sales", "sales", "DATABASE");
        MetadataCapture capture =
                service(manager).collect(selection, () -> false);

        assertThat(capture.tableCount()).isEqualTo(1);
        assertThat(capture.columnCount()).isZero();
        assertThat(capture.snapshot().dataSources().get(0)
                .databases().get(0).schemas().get(0)
                .tables().get(0).type()).isEqualTo("COLLECTION");
        verify(manager).tree("connection-1", "sales");
        verify(manager, never()).columns(
                anyString(), any(), anyString());
    }

    @Test
    void cancelledCaptureMustStopBeforeConnectingOrEnumerating() {
        NativeConnectionManager manager = mock(NativeConnectionManager.class);
        MetadataContextService service = service(manager);

        assertThatThrownBy(() -> service.collect(
                selection("MYSQL"), () -> true))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(manager);
    }

    @Test
    void sqlServerTableCaptureMustSeparateCatalogAndSchema()
            throws Exception {
        NativeConnectionManager manager = mock(NativeConnectionManager.class);
        DesktopConnection connection = new DesktopConnection();
        connection.setId("connection-1");
        connection.setName("连接级数据库");
        connection.setDbType("MSSQL");
        when(manager.requireSaved("connection-1")).thenReturn(connection);
        when(manager.isConnected("connection-1")).thenReturn(true);
        when(manager.columns(
                "connection-1", "sales/dbo", "orders"))
                .thenReturn(List.of());

        MetadataSelection selection = new MetadataSelection(
                "connection-1", "MSSQL",
                MetadataSelection.Scope.TABLE,
                "orders", "sales/dbo/orders", "TABLE");
        MetadataCapture capture =
                service(manager).collect(selection, () -> false);

        var database = capture.snapshot().dataSources().get(0)
                .databases().get(0);
        assertThat(database.name()).isEqualTo("sales");
        assertThat(database.schemas()).singleElement()
                .satisfies(schema ->
                        assertThat(schema.name()).isEqualTo("dbo"));
        verify(manager).columns(
                "connection-1", "sales/dbo", "orders");
    }

    @Test
    void tableCaptureMustUseOnlyColumnMetadata() throws Exception {
        NativeConnectionManager manager = mock(NativeConnectionManager.class);
        DesktopConnection connection = new DesktopConnection();
        connection.setId("connection-1");
        connection.setName("测试库");
        connection.setDbType("MYSQL");
        when(manager.requireSaved("connection-1")).thenReturn(connection);
        when(manager.isConnected("connection-1")).thenReturn(true);
        ColumnMetadata column = new ColumnMetadata();
        column.setName("id");
        column.setDataType("BIGINT");
        column.setTypeName("BIGINT");
        column.setNullable(false);
        column.setPrimaryKey(true);
        when(manager.columns("connection-1", "sales", "orders"))
                .thenReturn(List.of(column));

        MetadataCapture capture =
                service(manager).collect(selection("MYSQL"), () -> false);

        assertThat(capture.tableCount()).isEqualTo(1);
        assertThat(capture.columnCount()).isEqualTo(1);
        assertThat(capture.estimatedTokens()).isPositive();
        var database = capture.snapshot().dataSources().get(0)
                .databases().get(0);
        assertThat(database.name()).isEqualTo("sales");
        assertThat(database.schemas()).singleElement()
                .satisfies(schema ->
                        assertThat(schema.name()).isEmpty());
        verify(manager).columns("connection-1", "sales", "orders");
        verify(manager, never()).tree(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private static MetadataContextService service(NativeConnectionManager manager) {
        return new MetadataContextService(
                manager, new MetadataSnapshotRenderer());
    }

    private static MetadataSelection selection(String dbType) {
        return new MetadataSelection(
                "connection-1", dbType, MetadataSelection.Scope.TABLE,
                "orders", "sales/orders", "TABLE");
    }
}
