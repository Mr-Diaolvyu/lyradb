package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseMetadataCatalogServiceTest {

    private GrantService grantService;
    private DataSourceService dataSourceService;
    private ApprovalSecurityContextService securityContextService;
    private EnterpriseMetadataCatalogService service;
    private DatabaseDriver driver;
    private Object connection;
    private Grant grant;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        grantService = mock(GrantService.class);
        dataSourceService = mock(DataSourceService.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        securityContextService =
                mock(ApprovalSecurityContextService.class);
        service = new EnterpriseMetadataCatalogService(
                grantService, dataSourceService,
                securityUtil, securityContextService);

        User user = new User();
        user.setId("user-1");
        grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        grant.setAllowedSchemas("public");
        grant.setAllowedTables("public.orders,public.secret");
        grant.setBlockedTables("public.secret");

        dataSource = new DataSource();
        dataSource.setId("source-1");
        dataSource.setWorkspaceId("workspace-1");
        dataSource.setDbType("POSTGRESQL");

        when(securityUtil.requireCurrentUser())
                .thenReturn(user);
        when(securityUtil.requireCurrentWorkspace())
                .thenReturn("workspace-1");
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "sales"))
                .thenReturn(grant);
        when(grantService.getByIdForUser(
                "grant-1", "user-1", "workspace-1"))
                .thenReturn(grant);
        when(dataSourceService.getEntity("source-1"))
                .thenReturn(dataSource);
        when(securityContextService.fingerprint(grant))
                .thenReturn("fingerprint-1");

        driver = mock(DatabaseDriver.class);
        connection = new Object();
        when(dataSourceService.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(
                        driver, connection));
    }

    @Test
    void catalogOnlyReturnsWhitelistedAndNonBlockedTables()
            throws Exception {
        TreeNode schema = TreeNode.of(
                "public", "public", "SCHEMA", "public");
        TreeNode orders = TreeNode.of(
                "public/orders", "orders",
                "TABLE", "public/orders");
        TreeNode secret = TreeNode.of(
                "public/secret", "secret",
                "TABLE", "public/secret");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(schema));
        when(driver.getTreeNodes(connection, "public"))
                .thenReturn(List.of(orders, secret));

        EnterpriseMetadataCatalog catalog =
                service.catalog("sales", true);

        assertThat(catalog.getGrantedSourceName())
                .isEqualTo("sales");
        assertThat(catalog.getDbType())
                .isEqualTo("POSTGRESQL");
        assertThat(catalog.getSchemas())
                .containsExactly("public");
        assertThat(catalog.getTables())
                .extracting(
                        EnterpriseMetadataCatalog.Table::getQualifiedName)
                .containsExactly("public.orders");
    }

    @Test
    void columnsRequireSchemaAndTableToMatchAsOneAuthorizationPair()
            throws Exception {
        // 仅允许末级 Schema，但表白名单属于另一种完整限定方式；
        // 两者不能拼接后跨库复用。
        grant.setAllowedSchemas("public");
        grant.setAllowedTables("sales.public.orders");
        grant.setBlockedTables(null);

        assertThatThrownBy(() -> service.columns(
                "sales", "sales/public", "orders"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("授权范围");

        verify(dataSourceService, never())
                .resolveActiveConnection("source-1");
    }

    @Test
    void authorizedColumnsUseDriverNamespaceWithoutExposingConnection()
            throws Exception {
        ColumnMetadata column = new ColumnMetadata();
        column.setName("order_id");
        column.setRemarks("订单编号");
        when(driver.getTableColumns(
                connection, "public", "orders"))
                .thenReturn(List.of(column));

        List<ColumnMetadata> columns =
                service.columns(
                        "sales", "public", "orders");

        assertThat(columns)
                .extracting(ColumnMetadata::getRemarks)
                .containsExactly("订单编号");
        verify(driver).getTableColumns(
                connection, "public", "orders");
    }

    @Test
    void erDiagramOnlyLoadsExplicitlySelectedAuthorizedTables()
            throws Exception {
        grant.setAllowedTables(
                "public.orders,public.customers,public.inventory");
        grant.setBlockedTables(null);
        TreeNode schema = TreeNode.of(
                "public", "public", "SCHEMA", "public");
        TreeNode orders = TreeNode.of(
                "public/orders", "orders", "TABLE", "public/orders");
        TreeNode customers = TreeNode.of(
                "public/customers", "customers", "TABLE", "public/customers");
        TreeNode inventory = TreeNode.of(
                "public/inventory", "inventory", "TABLE", "public/inventory");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(schema));
        when(driver.getTreeNodes(connection, "public"))
                .thenReturn(List.of(orders, customers, inventory));

        ColumnMetadata customerId = new ColumnMetadata();
        customerId.setName("customer_id");
        customerId.setTypeName("BIGINT");
        customerId.setRemarks("客户编号");
        ColumnMetadata id = new ColumnMetadata();
        id.setName("id");
        id.setTypeName("BIGINT");
        id.setPrimaryKey(true);
        when(driver.getTableColumns(connection, "public", "orders"))
                .thenReturn(List.of(customerId));
        when(driver.getTableColumns(connection, "public", "customers"))
                .thenReturn(List.of(id));

        TableConstraintMetadata foreignKey =
                new TableConstraintMetadata();
        foreignKey.setType("FOREIGN_KEY");
        foreignKey.setColumns(List.of("customer_id"));
        foreignKey.setReferencedTable("public.customers");
        foreignKey.setReferencedColumns(List.of("id"));
        when(driver.getTableConstraints(
                connection, "public", "orders"))
                .thenReturn(List.of(foreignKey));
        when(driver.getTableConstraints(
                connection, "public", "customers"))
                .thenReturn(List.of());

        ErDiagram diagram = service.erDiagram(
                "sales", "public", List.of("orders", "customers"));

        assertThat(diagram.getTables())
                .extracting(ErDiagram.Table::getName)
                .containsExactly("orders", "customers");
        assertThat(diagram.getEdges()).singleElement()
                .satisfies(edge -> {
                    assertThat(edge.getSource()).isEqualTo("orders");
                    assertThat(edge.getTarget()).isEqualTo("customers");
                    assertThat(edge.getSourceColumn())
                            .isEqualTo("customer_id");
                    assertThat(edge.getTargetColumn()).isEqualTo("id");
                });
        verify(driver, never()).getTableColumns(
                connection, "public", "inventory");
        verify(driver, never()).getTableConstraints(
                connection, "public", "inventory");
    }

    @Test
    void erDiagramRejectsMoreThanTwentyFourTablesBeforeConnecting() {
        List<String> selected = java.util.stream.IntStream
                .rangeClosed(1, 25)
                .mapToObj(index -> "table_" + index)
                .toList();

        assertThatThrownBy(() -> service.erDiagram(
                "sales", "public", selected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多选择 24 张表");
        verify(dataSourceService, never())
                .resolveActiveConnection("source-1");
    }

    @Test
    void maxComputeDiagramLoadsColumnsWithoutGuessingRelationships()
            throws Exception {
        dataSource.setDbType("MAXCOMPUTE");
        grant.setAllowedSchemas("project_one");
        grant.setAllowedTables("project_one.fact_orders");
        grant.setBlockedTables(null);
        TreeNode table = TreeNode.of(
                "fact_orders", "fact_orders", "TABLE", "fact_orders");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(table));
        ColumnMetadata id = new ColumnMetadata();
        id.setName("order_id");
        id.setTypeName("STRING");
        when(driver.getTableColumns(
                connection, "project_one", "fact_orders"))
                .thenReturn(List.of(id));

        ErDiagram diagram = service.erDiagram(
                "sales", "project_one", List.of("fact_orders"));

        assertThat(diagram.getDbType()).isEqualTo("MAXCOMPUTE");
        assertThat(diagram.getTables())
                .extracting(ErDiagram.Table::getName)
                .containsExactly("fact_orders");
        assertThat(diagram.getEdges()).isEmpty();
        verify(driver, never()).getTableConstraints(
                connection, "project_one", "fact_orders");
    }
}
