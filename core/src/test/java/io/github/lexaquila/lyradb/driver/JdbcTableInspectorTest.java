package io.github.lexaquila.lyradb.driver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcTableInspectorTest {

    private Connection connection;
    private DatabaseMetaData metadata;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
    }

    @Test
    void shouldBuildQuotedMysqlPreviewAndClampLimit() throws Exception {
        String sql = JdbcTableInspector.previewSql(
                connection, "MYSQL", "erp/base", "order`detail", 5_000);

        assertThat(sql).isEqualTo(
                "SELECT * FROM `erp`.`base`.`order``detail` LIMIT 1000");
    }

    @Test
    void shouldBuildSqlServerTopPreview() throws Exception {
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");

        String sql = JdbcTableInspector.previewSql(
                connection, "MSSQL", "catalog/dbo", "orders", 200);

        assertThat(sql).isEqualTo(
                "SELECT TOP (200) * FROM \"catalog\".\"dbo\".\"orders\"");
    }

    @Test
    void shouldBuildOracleRownumPreview() throws Exception {
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");

        String sql = JdbcTableInspector.previewSql(
                connection, "ORACLE", "APP", "ORDERS", 0);

        assertThat(sql).isEqualTo(
                "SELECT * FROM \"APP\".\"ORDERS\" WHERE ROWNUM <= 1");
    }
}
