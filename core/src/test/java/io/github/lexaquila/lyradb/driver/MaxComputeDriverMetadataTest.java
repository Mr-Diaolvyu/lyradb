package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxComputeDriverMetadataTest {

    @Test
    void shouldSplitShowTablesPayloadAndAvoidLegacyMetadataEnumeration()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW TABLES")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(
                "v4_100:orders\np4_200:customers");

        MaxComputeDriver driver = driver();
        var nodes = driver.getTreeNodes(connection, null);

        assertThat(nodes).extracting("name")
                .containsExactly("customers", "orders");
        verify(connection, never()).getMetaData();
    }

    @Test
    void shouldFilterSearchOnServerWithSafeShowTablesLike()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW TABLES LIKE '*ord*'"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(
                "v4_100:orders\nv4_100:order_items");

        var nodes = driver().searchTreeNodes(connection, "ord", 20);

        assertThat(nodes).extracting("name")
                .containsExactly("order_items", "orders");
        verify(connection, never()).getMetaData();
    }

    @Test
    void shouldBuildBoundedPreviewSqlAndRejectUnsafeIdentifiers() {
        MaxComputeDriver driver = driver();

        assertThat(driver.buildTablePreviewSql(
                mock(Connection.class), null, "orders", 2_000))
                .isEqualTo("SELECT * FROM orders TABLESAMPLE (1000 ROWS)");
        assertThatThrownBy(() -> driver.buildTablePreviewSql(
                mock(Connection.class), null, "orders; DROP TABLE x", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的表标识符");
    }

    @Test
    void shouldUseOfficialExecuteQueryPathWhenMaxRowsIsUnsupported()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.doThrow(new SQLFeatureNotSupportedException())
                .when(statement).setMaxRows(20);
        when(statement.executeQuery("SELECT id FROM orders"))
                .thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(42L);

        var result = driver().executeQuery(
                connection, "SELECT id FROM orders", 20);

        assertThat(result.getColumns()).containsExactly("id");
        assertThat(result.getRows()).containsExactly(
                java.util.Map.of("id", 42L));
        verify(statement, never()).execute("SELECT id FROM orders");
    }

    @Test
    void shouldEnrichBlankJdbcRemarksFromDescribe() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet jdbcColumns = mock(ResultSet.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        ResultSet describe = mock(ResultSet.class);
        ResultSetMetaData describeMetaData = mock(ResultSetMetaData.class);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getColumns(null, null, "orders", "%"))
                .thenReturn(jdbcColumns);
        when(jdbcColumns.next()).thenReturn(true, false);
        when(jdbcColumns.getString("COLUMN_NAME")).thenReturn("order_id");
        when(jdbcColumns.getInt("DATA_TYPE")).thenReturn(Types.BIGINT);
        when(jdbcColumns.getString("TYPE_NAME")).thenReturn("BIGINT");
        when(jdbcColumns.getInt("NULLABLE"))
                .thenReturn(DatabaseMetaData.columnNullable);
        when(jdbcColumns.getString("TABLE_CAT")).thenReturn("demo_project");
        when(jdbcColumns.getString("TABLE_SCHEM")).thenReturn(null);
        when(jdbcColumns.getString("REMARKS")).thenReturn(null);
        when(databaseMetaData.getPrimaryKeys(null, null, "orders"))
                .thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE orders")).thenReturn(describe);
        when(describe.getMetaData()).thenReturn(describeMetaData);
        when(describeMetaData.getColumnCount()).thenReturn(3);
        when(describe.next()).thenReturn(true, false);
        when(describe.getString(1)).thenReturn("order_id");
        when(describe.getString(2)).thenReturn("BIGINT");
        when(describe.getString(3)).thenReturn("订单编号");

        var columns = driver().getTableColumns(
                connection, null, "orders");

        assertThat(columns).singleElement().satisfies(column -> {
            assertThat(column.getName()).isEqualTo("order_id");
            assertThat(column.getRemarks()).isEqualTo("订单编号");
        });
    }

    @Test
    void shouldReadTableCommentFromDescribeExtended() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE EXTENDED orders"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("Comment");
        when(resultSet.getString(2)).thenReturn("订单事实表");

        assertThat(driver().getTableComment(
                connection, null, "orders"))
                .isEqualTo("订单事实表");
    }

    @Test
    void shouldReadTableCommentFromSingleColumnExtendedPayload()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE EXTENDED orders"))
                .thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(
                "Owner: data_team\nComment: 订单事实表\nLifecycle: 365");

        assertThat(driver().getTableComment(
                connection, null, "orders"))
                .isEqualTo("订单事实表");
    }

    private static MaxComputeDriver driver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("MAXCOMPUTE");
        info.setCapabilities(new DriverCapability());
        return new MaxComputeDriver(
                info, MaxComputeDriverMetadataTest.class.getClassLoader());
    }
}
