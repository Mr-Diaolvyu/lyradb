package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractJdbcDriverResultTest {

    @Test
    void shouldDisambiguateDuplicateColumnLabelsWithoutDroppingValues() {
        Map<String, Integer> occurrences = new HashMap<>();

        assertThat(AbstractJdbcDriver.disambiguateColumnLabel("id", occurrences))
                .isEqualTo("id");
        assertThat(AbstractJdbcDriver.disambiguateColumnLabel("ID", occurrences))
                .isEqualTo("ID (2)");
        assertThat(AbstractJdbcDriver.disambiguateColumnLabel("", occurrences))
                .isEqualTo("column");
    }

    @Test
    void shouldQuoteIdentifiersAndKeepCommaBeforePrimaryKey() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metadata);

        String ddl = new StubDriver().getTableDDL(
                connection, "public", "order");

        assertThat(ddl)
                .contains("CREATE TABLE \"public\".\"order\"")
                .contains("\"id\" INTEGER NOT NULL,")
                .contains("\"select\" VARCHAR(20),")
                .contains("PRIMARY KEY (\"id\")");
    }

    @Test
    void shouldEscapeClosingIdentifierQuote() {
        assertThat(AbstractJdbcDriver.quoteIdentifier("a\"b", "\""))
                .isEqualTo("\"a\"\"b\"");
        assertThat(AbstractJdbcDriver.quoteIdentifier("a]b", "["))
                .isEqualTo("[a]]b]");
    }

    private static final class StubDriver extends AbstractJdbcDriver {

        private StubDriver() {
            super(driverInfo(), StubDriver.class.getClassLoader());
        }

        @Override
        public List<ColumnMetadata> getTableColumns(
                Object connection, String schemaName, String tableName) {
            List<ColumnMetadata> columns = new ArrayList<>();
            ColumnMetadata id = new ColumnMetadata();
            id.setName("id");
            id.setTypeName("INTEGER");
            id.setDataType(String.valueOf(Types.INTEGER));
            id.setNullable(false);
            id.setPrimaryKey(true);
            columns.add(id);

            ColumnMetadata reserved = new ColumnMetadata();
            reserved.setName("select");
            reserved.setTypeName("VARCHAR");
            reserved.setDataType(String.valueOf(Types.VARCHAR));
            reserved.setColumnSize(20);
            columns.add(reserved);
            return columns;
        }

        @Override
        protected String buildIndexDdl(
                Connection conn, String schemaName, String tableName) {
            return "";
        }

        @Override
        protected String getTableComment(
                Connection conn, String schemaName, String tableName) {
            return null;
        }

        private static DriverInfo driverInfo() {
            DriverInfo info = new DriverInfo();
            info.setDbType("POSTGRESQL");
            info.setCapabilities(new DriverCapability());
            return info;
        }
    }
}
