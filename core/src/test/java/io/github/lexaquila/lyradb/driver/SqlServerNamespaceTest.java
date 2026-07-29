package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlServerNamespaceTest {

    @Test
    void shouldNavigateCatalogThenSchemaThenTables() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metadata);

        ResultSet schemas = resultSetWithSingleString("TABLE_SCHEM", "dbo");
        when(metadata.getSchemas("sales", "%")).thenReturn(schemas);
        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("orders");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(metadata.getTables(
                eq("sales"), eq("dbo"), eq("%"), any(String[].class)))
                .thenReturn(tables);

        GenericJdbcDriver driver = new GenericJdbcDriver(
                sqlServerInfo(), getClass().getClassLoader());
        List<TreeNode> schemaNodes = driver.getTreeNodes(connection, "sales");
        List<TreeNode> tableNodes = driver.getTreeNodes(connection, "sales/dbo");

        assertThat(schemaNodes).singleElement().satisfies(node -> {
            assertThat(node.getName()).isEqualTo("dbo");
            assertThat(node.getPath()).isEqualTo("sales/dbo");
        });
        assertThat(tableNodes).singleElement().satisfies(node -> {
            assertThat(node.getName()).isEqualTo("orders");
            assertThat(node.getPath()).isEqualTo("sales/dbo/orders");
        });
    }

    @Test
    void shouldUseBothCatalogAndSchemaForColumnMetadata() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getColumns(
                "sales", "dbo", "orders", "%"))
                .thenReturn(mock(ResultSet.class));
        when(metadata.getPrimaryKeys(
                "sales", "dbo", "orders"))
                .thenReturn(mock(ResultSet.class));

        GenericJdbcDriver driver = new GenericJdbcDriver(
                sqlServerInfo(), getClass().getClassLoader());
        driver.getTableColumns(connection, "sales/dbo", "orders");

        verify(metadata).getColumns("sales", "dbo", "orders", "%");
        verify(metadata).getPrimaryKeys("sales", "dbo", "orders");
    }

    private static ResultSet resultSetWithSingleString(
            String column, String value) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString(column)).thenReturn(value);
        return result;
    }

    private static DriverInfo sqlServerInfo() {
        DriverInfo info = new DriverInfo();
        info.setDbType("MSSQL");
        info.setCapabilities(new DriverCapability());
        return info;
    }
}
