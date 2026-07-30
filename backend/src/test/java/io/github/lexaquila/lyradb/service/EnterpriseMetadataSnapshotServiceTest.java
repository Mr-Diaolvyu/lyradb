package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.security.access.AccessDeniedException;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnterpriseMetadataSnapshotServiceTest {

    @Mock
    private GrantService grantService;
    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private ApprovalSecurityContextService securityContextService;
    @Mock
    private DatabaseDriver driver;

    private final MetadataSnapshotSessionStore store =
            new MetadataSnapshotSessionStore();
    private EnterpriseMetadataSnapshotService service;
    private User owner;
    private Object connection;

    @BeforeEach
    void setUp() {
        service = new EnterpriseMetadataSnapshotService(
                grantService, dataSourceService, securityContextService,
                store);
        owner = new User();
        owner.setId("user-1");
        connection = new Object();
    }

    @AfterEach
    void tearDown() {
        store.clear();
    }

    @Test
    void sameTableNameInOtherDatabaseCannotSupplyColumns()
            throws Exception {
        Grant grant = prepare(
                "MYSQL", "db_b.orders", "db_b");
        TreeNode databaseA =
                node("db_a", "DATABASE", "db_a");
        TreeNode databaseB =
                node("db_b", "DATABASE", "db_b");
        TreeNode orders =
                node("orders", "TABLE", "db_b/orders");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(databaseA, databaseB));
        when(driver.getTreeNodes(connection, "db_b"))
                .thenReturn(List.of(orders));
        when(driver.getTableColumns(connection, "db_b", "orders"))
                .thenReturn(List.of(column("tenant_b_id")));

        EnterpriseMetadataSnapshotService.CaptureResult result =
                service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", "db_b",
                                List.of(), List.of("db_b.orders")));

        assertEquals(1, result.tableCount());
        assertEquals("source-1", result.dataSourceId());
        assertEquals(64, result.contentSha256().length());
        assertEquals(List.of("tenant_b_id"),
                result.preview().get(0).columns());
        verify(driver, never())
                .getTreeNodes(connection, "db_a");
        verify(driver).getTableColumns(
                connection, "db_b", "orders");
        verify(driver, never()).getTableColumns(
                connection, null, "orders");
        assertEquals("grant-1", grant.getId());
    }

    @Test
    void selectedSchemaIsFilteredBeforeTraversal() throws Exception {
        prepare("POSTGRESQL", "sales.*,secret.*", "sales,secret");
        TreeNode secret =
                node("secret", "SCHEMA", "secret");
        TreeNode sales =
                node("sales", "SCHEMA", "sales");
        TreeNode orders =
                node("orders", "TABLE", "sales/orders");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(secret, sales));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(orders));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id")));

        service.capture(
                "workspace-1", owner,
                new EnterpriseMetadataSnapshotService.CaptureRequest(
                        "sales-source", null,
                        List.of("sales"), List.of()));

        verify(driver, never())
                .getTreeNodes(connection, "secret");
        verify(driver).getTreeNodes(connection, "sales");
    }

    @Test
    void tableWhitelistCannotBypassSchemaWhitelist() throws Exception {
        prepare("MYSQL", "schema2.*", "schema1");
        TreeNode database =
                node("schema2", "DATABASE", "schema2");
        TreeNode table =
                node("orders", "TABLE", "schema2/orders");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(database));

        EnterpriseMetadataSnapshotService.CaptureResult result =
                service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", "schema2",
                                List.of(), List.of()));

        assertEquals(0, result.tableCount());
        verify(driver, never()).getTableColumns(
                any(), any(), any());
        verify(driver, never())
                .getTreeNodes(connection, "schema2");
    }

    @Test
    void unauthorizedRootSchemaIsNeverTraversed() throws Exception {
        prepare("POSTGRESQL", "sales.orders", "sales");
        TreeNode secret = node("secret", "SCHEMA", "secret");
        TreeNode sales = node("sales", "SCHEMA", "sales");
        TreeNode orders = node("orders", "TABLE", "sales/orders");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(secret, sales));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(orders));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id")));

        EnterpriseMetadataSnapshotService.CaptureResult result =
                service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", "warehouse",
                                List.of(), List.of()));

        assertEquals(1, result.tableCount());
        verify(driver, never()).getTreeNodes(connection, "secret");
        verify(driver).getTreeNodes(connection, "sales");
    }

    @Test
    void grantShrinkAfterScanRejectsSnapshot() throws Exception {
        Grant initial = prepare(
                "POSTGRESQL", "sales.orders", "sales");
        Grant narrowed = copyGrant(
                initial, "secret.audit", "secret");
        when(grantService.getByIdForUser(
                "grant-1", "user-1", "workspace-1"))
                .thenReturn(narrowed);
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("sales", "SCHEMA", "sales")));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(
                        node("orders", "TABLE", "sales/orders")));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id")));

        assertThrows(AccessDeniedException.class,
                () -> service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", null,
                                List.of("sales"), List.of())));

        verify(driver).getTableColumns(
                connection, "sales", "orders");
    }

    @Test
    void revokedGrantAfterScanRejectsSnapshot() throws Exception {
        prepare("POSTGRESQL", "sales.orders", "sales");
        when(grantService.getByIdForUser(
                "grant-1", "user-1", "workspace-1"))
                .thenThrow(new AccessDeniedException("授权已撤销"));
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("sales", "SCHEMA", "sales")));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(
                        node("orders", "TABLE", "sales/orders")));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id")));

        assertThrows(AccessDeniedException.class,
                () -> service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", null,
                                List.of("sales"), List.of())));

        verify(driver).getTableColumns(
                connection, "sales", "orders");
    }

    @Test
    void fingerprintChangeAfterScanRejectsSnapshot() throws Exception {
        prepare("POSTGRESQL", "sales.orders", "sales");
        when(securityContextService.fingerprint(any(Grant.class)))
                .thenReturn("before", "after");
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("sales", "SCHEMA", "sales")));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(
                        node("orders", "TABLE", "sales/orders")));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id")));

        assertThrows(IllegalStateException.class,
                () -> service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", null,
                                List.of("sales"), List.of())));

        verify(driver).getTableColumns(
                connection, "sales", "orders");
    }
    @Test
    void moreThanTwoHundredAuthorizedTablesFailsExplicitly()
            throws Exception {
        prepare("MYSQL", "analytics.*", "analytics");
        TreeNode database =
                node("analytics", "DATABASE", "analytics");
        List<TreeNode> tables = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            tables.add(node(
                    "table_" + index, "TABLE",
                    "analytics/table_" + index));
        }
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(database));
        when(driver.getTreeNodes(connection, "analytics"))
                .thenReturn(tables);
        when(driver.getTableColumns(
                eq(connection), eq("analytics"), any()))
                .thenReturn(List.of(column("id")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(
                        "workspace-1", owner,
                        new EnterpriseMetadataSnapshotService.CaptureRequest(
                                "sales-source", "analytics",
                                List.of(), List.of())));

        assertTrue(exception.getMessage().contains("200"));
        verify(driver, times(200)).getTableColumns(
                eq(connection), eq("analytics"), any());
    }

    private static Grant copyGrant(
            Grant source, String allowedTables,
            String allowedSchemas) {
        Grant copy = new Grant();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setDataSourceId(source.getDataSourceId());
        copy.setGrantedSourceName(source.getGrantedSourceName());
        copy.setAllowedTables(allowedTables);
        copy.setAllowedSchemas(allowedSchemas);
        copy.setBlockedTables(source.getBlockedTables());
        copy.setSqlCapability(source.getSqlCapability());
        return copy;
    }
    private Grant prepare(
            String dbType, String allowedTables,
            String allowedSchemas) {
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setUserId("user-1");
        grant.setWorkspaceId("workspace-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales-source");
        grant.setAllowedTables(allowedTables);
        grant.setAllowedSchemas(allowedSchemas);
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);
        lenient().when(grantService.getByIdForUser(
                "grant-1", "user-1", "workspace-1"))
                .thenReturn(grant);

        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType(dbType);
        when(dataSourceService.getEntity("source-1"))
                .thenReturn(source);
        when(dataSourceService.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(
                        driver, connection));
        lenient().when(securityContextService.fingerprint(grant))
                .thenReturn("fingerprint");
        return grant;
    }

    private static TreeNode node(
            String name, String type, String path) {
        return TreeNode.of(path, name, type, path);
    }

    private static ColumnMetadata column(String name) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setTypeName("VARCHAR");
        return column;
    }
}
