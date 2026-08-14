package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxComputeDriverMetadataTest {

    private static final String INFORMATION_SCHEMA_TABLES_SQL =
            "SELECT table_name, table_type, table_comment, is_partitioned "
                    + "FROM INFORMATION_SCHEMA.TABLES LIMIT 20000";
    private static final String TENANT_INFORMATION_SCHEMA_TABLES_SQL =
            "SELECT table_name, table_type, table_comment, is_partitioned "
                    + "FROM SYSTEM_CATALOG.INFORMATION_SCHEMA.TABLES "
                    + "WHERE table_catalog = 'demo_project' LIMIT 20000";

    @Test
    void shouldLoadChineseCommentsAndViewsInOneBoundedCatalogQuery()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(INFORMATION_SCHEMA_TABLES_SQL))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString(1)).thenReturn(
                "orders", "empty_comment", "project_summary");
        when(resultSet.getString(2)).thenReturn(
                "MANAGED_TABLE", "TABLE", "VIEW");
        when(resultSet.getString(3)).thenReturn(
                "订单事实表", "  ", "项目汇总视图");

        var nodes = driver().getTreeNodes(connection, null);

        assertThat(nodes).extracting("name")
                .containsExactly("empty_comment", "orders", "project_summary");
        assertThat(nodes.get(0).getProperties())
                .doesNotContainKey("remarks");
        assertThat(nodes.get(1).getProperties())
                .containsEntry("remarks", "订单事实表")
                .containsEntry("metadataSource", "PROJECT_INFORMATION_SCHEMA")
                .containsEntry("metadataStatus", "COMPLETE")
                .containsEntry("remarksStatus", "AVAILABLE");
        assertThat(nodes.get(2).getType()).isEqualTo("VIEW");
        assertThat(nodes.get(2).getProperties())
                .containsEntry("remarks", "项目汇总视图");
        verify(statement, never()).executeQuery("SHOW TABLES");
    }

    @Test
    void shouldSplitShowTablesPayloadAndAvoidLegacyMetadataEnumeration()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(INFORMATION_SCHEMA_TABLES_SQL))
                .thenThrow(new SQLException("无 INFORMATION_SCHEMA 权限"));
        when(statement.executeQuery("SHOW TABLES")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(
                "v4_100:orders\np4_200:customers");

        MaxComputeDriver driver = driver();
        var nodes = driver.getTreeNodes(connection, null);

        assertThat(nodes).extracting("name")
                .containsExactly("customers", "orders");
        assertThat(nodes).allSatisfy(node -> assertThat(node.getProperties())
                .containsEntry("metadataSource", "SHOW_TABLES")
                .containsEntry("metadataStatus", "FALLBACK")
                .containsEntry("remarksStatus", "UNAVAILABLE"));
    }

    @Test
    void shouldFilterSearchAfterObservableShowTablesFallback()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(INFORMATION_SCHEMA_TABLES_SQL))
                .thenThrow(new SQLException("旧版服务端不支持"));
        when(statement.executeQuery("SHOW TABLES"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(
                "v4_100:orders\nv4_100:order_items");

        var nodes = driver().searchTreeNodes(connection, "ord", 20);

        assertThat(nodes).extracting("name")
                .containsExactly("order_items", "orders");
    }

    @Test
    void shouldSearchChineseBusinessNameFromTableComment()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(INFORMATION_SCHEMA_TABLES_SQL))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("orders", "customers");
        when(resultSet.getString(2)).thenReturn("TABLE", "TABLE");
        when(resultSet.getString(3)).thenReturn(
                "项目订单事实表", "客户主数据");

        var nodes = driver().searchTreeNodes(connection, "项目", 20);

        assertThat(nodes).singleElement().satisfies(node -> {
            assertThat(node.getName()).isEqualTo("orders");
            assertThat(node.getProperties())
                    .containsEntry("remarks", "项目订单事实表");
        });
        verify(statement, never())
                .executeQuery("SHOW TABLES LIKE '*项目*'");
    }

    @Test
    void shouldUseTenantInformationSchemaAndExposePartitionFlag()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getCatalog()).thenReturn("demo_project");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(TENANT_INFORMATION_SCHEMA_TABLES_SQL))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("orders");
        when(resultSet.getString(2)).thenReturn("MANAGED_TABLE");
        when(resultSet.getString(3)).thenReturn("订单事实表");
        when(resultSet.getObject(4)).thenReturn(true);

        var nodes = driver().getTreeNodes(connection, null);

        assertThat(nodes).singleElement().satisfies(node ->
                assertThat(node.getProperties())
                        .containsEntry("remarks", "订单事实表")
                        .containsEntry("partitioned", true)
                        .containsEntry("metadataSource",
                                "TENANT_INFORMATION_SCHEMA")
                        .containsEntry("metadataStatus", "COMPLETE"));
        verify(statement, never())
                .executeQuery(INFORMATION_SCHEMA_TABLES_SQL);
    }

    @Test
    void shouldBulkEnrichShowTablesWithSdkCommentAndPartitions()
            throws Exception {
        FakeTable table = FakeTable.partitioned(
                "orders", "订单事实表", List.of("ds", "region"),
                List.of("ds=20260814/region=hangzhou"));
        SdkConnection connection = sdkConnection(List.of(table));
        Statement statement = mock(Statement.class);
        ResultSet showTables = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(INFORMATION_SCHEMA_TABLES_SQL))
                .thenThrow(new SQLException("项目级视图未安装"));
        when(statement.executeQuery("SHOW TABLES")).thenReturn(showTables);
        when(showTables.next()).thenReturn(true, false);
        when(showTables.getString(1)).thenReturn("v4_100:orders");

        var nodes = driver().getTreeNodes(connection, null);

        assertThat(nodes).singleElement().satisfies(node -> {
            assertThat(node.getProperties())
                    .containsEntry("remarks", "订单事实表")
                    .containsEntry("remarksStatus", "AVAILABLE")
                    .containsEntry("partitioned", true)
                    .containsEntry("partitionKeys", "ds,region")
                    .containsEntry("metadataSource", "MAXCOMPUTE_JAVA_SDK")
                    .containsEntry("metadataStatus", "COMPLETE");
        });
    }

    @Test
    void shouldPageSdkPartitionsAndBuildFullPartitionPreview()
            throws Exception {
        FakeTable table = FakeTable.partitioned(
                "orders", "订单事实表", List.of("ds", "region"),
                List.of(
                        "ds=20260814/region=hangzhou",
                        "ds=20260813/region=shanghai",
                        "ds=20260812/region=beijing"));
        SdkConnection connection = sdkConnection(List.of(table));
        MaxComputeDriver driver = driver();

        var page = driver.listTablePartitions(
                connection, null, "orders", 1, 1);

        assertThat(page.isPartitioned()).isTrue();
        assertThat(page.getPartitionKeys()).containsExactly("ds", "region");
        assertThat(page.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getSpec())
                    .isEqualTo("ds=20260813/region=shanghai");
            assertThat(item.getValues()).containsExactly(
                    org.assertj.core.data.MapEntry.entry("ds", "20260813"),
                    org.assertj.core.data.MapEntry.entry("region", "shanghai"));
        });
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getOrdering()).isEqualTo("PARTITION_SPEC_DESC");

        assertThat(driver.buildPartitionPreviewSql(
                connection, null, "orders",
                "ds=20260814/region=hangzhou", 100))
                .isEqualTo("SELECT * FROM orders WHERE ds = '20260814' "
                        + "AND region = 'hangzhou' LIMIT 100");
        assertThatThrownBy(() -> driver.buildPartitionPreviewSql(
                connection, null, "orders", "ds=20260814", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完整");
    }

    @Test
    void shouldParseSlashSeparatedShowPartitionsWithHardPageBound()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet describe = mock(ResultSet.class);
        ResultSetMetaData describeMetadata = mock(ResultSetMetaData.class);
        ResultSet partitions = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE orders")).thenReturn(describe);
        when(describe.getMetaData()).thenReturn(describeMetadata);
        when(describeMetadata.getColumnCount()).thenReturn(3);
        when(describe.next()).thenReturn(true, true, true, false);
        when(describe.getString(1)).thenReturn(
                "# Partition Columns", "ds", "region");
        // 分区标记行会在读取类型前跳过，因此这里只为两个真实字段设值。
        when(describe.getString(2)).thenReturn("STRING", "STRING");
        when(describe.getString(3)).thenReturn(
                (String) null, (String) null);
        when(statement.executeQuery("SHOW PARTITIONS orders"))
                .thenReturn(partitions);
        when(partitions.next()).thenReturn(true, true, true, false);
        when(partitions.getString(1)).thenReturn(
                "ds=20260814/region=hangzhou",
                "ds=20260813/region=shanghai",
                "ds=20260812/region=beijing");

        var page = driver().listTablePartitions(
                connection, null, "orders", 1, 1);

        assertThat(page.getPartitionKeys()).containsExactly("ds", "region");
        assertThat(page.getItems()).singleElement().satisfies(item ->
                assertThat(item.getValues())
                        .containsEntry("ds", "20260813")
                        .containsEntry("region", "shanghai"));
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getMetadataSource()).isEqualTo("SHOW_PARTITIONS");
        verify(statement).setMaxRows(3);
    }

    @Test
    void shouldBuildBoundedPreviewSqlAndRejectUnsafeIdentifiers()
            throws Exception {
        MaxComputeDriver driver = driver();
        SdkConnection connection = sdkConnection(List.of(
                FakeTable.plain("orders", "订单事实表")));

        assertThat(driver.buildTablePreviewSql(
                connection, null, "orders", 2_000))
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

    @Test
    void shouldReadTableCommentFromSingleCellAsciiTablePayload()
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
                "| TableComment: 订单事实表 |");

        assertThat(driver().getTableComment(
                connection, null, "orders"))
                .isEqualTo("订单事实表");
    }

    @Test
    void shouldTreatBlankTableCommentAsMissingMetadata()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE EXTENDED orders"))
                .thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("TableComment");
        when(resultSet.getString(2)).thenReturn("   ");
        when(statement.executeQuery("SHOW CREATE TABLE orders"))
                .thenThrow(new SQLException("未开放 SHOW CREATE TABLE"));

        assertThat(driver().getTableComment(
                connection, null, "orders")).isNull();
    }

    @Test
    void shouldReturnNullWhenAllTableCommentSourcesFail()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("DESCRIBE EXTENDED orders"))
                .thenThrow(new SQLException("未开放 DESCRIBE EXTENDED"));
        when(statement.executeQuery("SHOW CREATE TABLE orders"))
                .thenThrow(new SQLException("未开放 SHOW CREATE TABLE"));

        assertThat(driver().getTableComment(
                connection, null, "orders")).isNull();
        assertThat(driver().getTableCommentMetadata(
                connection, null, "orders")).satisfies(metadata -> {
            assertThat(metadata.getRemarks()).isNull();
            assertThat(metadata.getRemarksStatus()).isEqualTo("UNAVAILABLE");
            assertThat(metadata.getMetadataStatus()).isEqualTo("PARTIAL");
            assertThat(metadata.getMetadataReason())
                    .contains("DESC EXTENDED", "SHOW CREATE TABLE");
        });
    }

    private static SdkConnection sdkConnection(List<FakeTable> tables) {
        SdkConnection connection = mock(SdkConnection.class);
        FakeOdps odps = new FakeOdps(new FakeTables(tables));
        when(connection.getOdps()).thenReturn(odps);
        when(connection.getExecuteProject()).thenReturn("demo_project");
        when(connection.isOdpsNamespaceSchema()).thenReturn(false);
        return connection;
    }

    interface SdkConnection extends Connection {

        FakeOdps getOdps();

        String getExecuteProject();

        boolean isOdpsNamespaceSchema();
    }

    public static final class FakeOdps {

        private final FakeTables tables;

        FakeOdps(FakeTables tables) {
            this.tables = tables;
        }

        public FakeTables tables() {
            return tables;
        }

        public String getDefaultProject() {
            return "demo_project";
        }

        public String getCurrentSchema() {
            return null;
        }
    }

    public static final class FakeTables {

        private final List<FakeTable> tables;

        FakeTables(List<FakeTable> tables) {
            this.tables = tables;
        }

        public List<FakeTable> loadTables(
                String project, Collection<String> names) {
            return tables.stream()
                    .filter(table -> names.contains(table.getName()))
                    .toList();
        }
    }

    public static final class FakeTable {

        private final String name;
        private final String comment;
        private final FakeSchema schema;
        private final List<FakePartition> partitions;

        private FakeTable(
                String name,
                String comment,
                List<String> partitionKeys,
                List<String> partitionSpecs) {
            this.name = name;
            this.comment = comment;
            this.schema = new FakeSchema(partitionKeys);
            this.partitions = partitionSpecs.stream()
                    .map(FakePartition::new)
                    .toList();
        }

        static FakeTable plain(String name, String comment) {
            return new FakeTable(name, comment, List.of(), List.of());
        }

        static FakeTable partitioned(
                String name,
                String comment,
                List<String> partitionKeys,
                List<String> partitionSpecs) {
            return new FakeTable(name, comment, partitionKeys, partitionSpecs);
        }

        public String getName() {
            return name;
        }

        public String getComment() {
            return comment;
        }

        public boolean isPartitioned() {
            return !schema.getPartitionColumns().isEmpty();
        }

        public boolean isVirtualView() {
            return false;
        }

        public FakeSchema getSchema() {
            return schema;
        }

        public Iterator<FakePartition> getPartitionIterator(
                Object prefix,
                boolean reverse,
                Long batchSize,
                Long limit) {
            return partitions.stream()
                    .limit(limit == null ? Long.MAX_VALUE : limit)
                    .iterator();
        }
    }

    public static final class FakeSchema {

        private final List<FakeColumn> partitionColumns;

        FakeSchema(List<String> names) {
            this.partitionColumns = names.stream()
                    .map(FakeColumn::new)
                    .toList();
        }

        public List<FakeColumn> getPartitionColumns() {
            return partitionColumns;
        }
    }

    public static final class FakeColumn {

        private final String name;

        FakeColumn(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final class FakePartition {

        private final String spec;

        FakePartition(String spec) {
            this.spec = spec;
        }

        public String getPartitionSpec() {
            return spec;
        }
    }

    private static MaxComputeDriver driver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("MAXCOMPUTE");
        info.setCapabilities(new DriverCapability());
        return new MaxComputeDriver(
                info, MaxComputeDriverMetadataTest.class.getClassLoader());
    }
}
