package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.MigrationRequest;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationServiceTest {

    @Test
    void createTableSeparatesLastColumnAndPrimaryKey() {
        MigrationService service = new MigrationService(mock(ConnectionService.class));
        MigrationRequest request = new MigrationRequest();
        request.setTargetTable("target_table");

        ColumnMetadata id = new ColumnMetadata();
        id.setName("id");
        id.setTypeName("INT");
        id.setNullable(false);
        id.setPrimaryKey(true);

        String ddl = service.buildCreateTable(request, List.of(id));

        assertTrue(ddl.contains("id INT NOT NULL,\n  PRIMARY KEY (id)"));
    }
    @Test
    void migrateQuotesReservedAndUnicodeJdbcIdentifiers() throws Exception {
        ConnectionService connectionService = mock(ConnectionService.class);
        DatabaseDriver sourceDriver = mock(DatabaseDriver.class);
        DatabaseDriver targetDriver = mock(DatabaseDriver.class);
        Connection sourceJdbc = mock(Connection.class);
        Connection targetJdbc = mock(Connection.class);
        DatabaseMetaData sourceMetadata = mock(DatabaseMetaData.class);
        DatabaseMetaData targetMetadata = mock(DatabaseMetaData.class);
        PreparedStatement insert = mock(PreparedStatement.class);

        when(sourceJdbc.getMetaData()).thenReturn(sourceMetadata);
        when(targetJdbc.getMetaData()).thenReturn(targetMetadata);
        when(sourceMetadata.getIdentifierQuoteString()).thenReturn("\"");
        when(targetMetadata.getIdentifierQuoteString()).thenReturn("\"");
        when(targetJdbc.prepareStatement(
                "INSERT INTO \"目标模式\".\"order\" (\"order\", \"名称\") VALUES (?, ?)"))
                .thenReturn(insert);
        when(insert.executeBatch()).thenReturn(new int[] { 1 });

        ColumnMetadata reserved = new ColumnMetadata();
        reserved.setName("order");
        reserved.setTypeName("INT");
        reserved.setNullable(false);
        reserved.setPrimaryKey(true);
        ColumnMetadata unicode = new ColumnMetadata();
        unicode.setName("名称");
        unicode.setTypeName("VARCHAR");
        unicode.setColumnSize(100);

        when(sourceDriver.getTableColumns(sourceJdbc, "源模式", "select"))
                .thenReturn(List.of(reserved, unicode));
        QueryResult data = new QueryResult();
        data.setTotalRows(1);
        data.addRow(Map.of("order", 1, "名称", "示例"));
        when(sourceDriver.executeQuery(sourceJdbc,
                "SELECT \"order\", \"名称\" FROM \"源模式\".\"select\"", 100_000))
                .thenReturn(data);

        when(connectionService.getActiveConnection("source"))
                .thenReturn(new ConnectionService.ActiveConnection(sourceDriver, sourceJdbc));
        when(connectionService.getActiveConnection("target"))
                .thenReturn(new ConnectionService.ActiveConnection(targetDriver, targetJdbc));

        MigrationRequest request = new MigrationRequest();
        request.setSourceConnectionId("source");
        request.setTargetConnectionId("target");
        request.setSourceSchema("源模式");
        request.setSourceTable("select");
        request.setTargetSchema("目标模式");
        request.setTargetTable("order");
        request.setMode("create");

        Map<String, Object> result = new MigrationService(connectionService).migrate(request);

        assertEquals(1, result.get("rowsRead"));
        assertEquals(1, result.get("rowsWritten"));
        verify(targetDriver).executeUpdate(eq(targetJdbc), argThat(sql ->
                sql.startsWith("CREATE TABLE \"目标模式\".\"order\"")
                        && sql.contains("\"order\" INT NOT NULL")
                        && sql.contains("\"名称\" VARCHAR(100)")
                        && sql.contains("PRIMARY KEY (\"order\")")));
        verify(targetJdbc).prepareStatement(
                "INSERT INTO \"目标模式\".\"order\" (\"order\", \"名称\") VALUES (?, ?)");
    }


}
