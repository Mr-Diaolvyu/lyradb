package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminGrantScopeServiceTest {
    @Test
    void listsDatabaseTablesAsQualifiedGrantOptions() throws Exception {
        DataSourceService sources = mock(DataSourceService.class);
        DatabaseDriver driver = mock(DatabaseDriver.class);
        Object connection = new Object();
        DataSource source = source("MYSQL");
        when(sources.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(driver, connection));
        when(sources.getMasked("source-1"))
                .thenReturn(Map.of("params", Map.of()));
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("sales", "DATABASE", "sales")));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(node("orders", "TABLE", "sales/orders"),
                        node("report", "VIEW", "sales/report")));

        AdminGrantScopeService.ScopeOptions result =
                new AdminGrantScopeService(sources).options(source);

        assertThat(result.namespaces()).extracting(
                AdminGrantScopeService.NamespaceOption::value)
                .containsExactly("sales");
        assertThat(result.tables()).containsExactly("sales.orders", "sales.report");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void preservesCatalogInQualifiedSqlServerTables() throws Exception {
        DataSourceService sources = mock(DataSourceService.class);
        DatabaseDriver driver = mock(DatabaseDriver.class);
        Object connection = new Object();
        when(sources.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(driver, connection));
        when(sources.getMasked("source-1"))
                .thenReturn(Map.of("params", Map.of()));
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("prod", "DATABASE", "prod")));
        when(driver.getTreeNodes(connection, "prod"))
                .thenReturn(List.of(node("sales", "SCHEMA", "prod/sales")));
        when(driver.getTreeNodes(connection, "prod/sales"))
                .thenReturn(List.of(node("orders", "TABLE", "prod/sales/orders")));

        AdminGrantScopeService.ScopeOptions result =
                new AdminGrantScopeService(sources).options(source("MSSQL"));

        assertThat(result.namespaces()).containsExactly(
                new AdminGrantScopeService.NamespaceOption(
                        "sales", "prod.sales", "prod.sales"));
        assertThat(result.tables()).containsExactly("prod.sales.orders");
    }

    @Test
    void usesConfiguredProjectForMaxComputeRootTables() throws Exception {
        DataSourceService sources = mock(DataSourceService.class);
        DatabaseDriver driver = mock(DatabaseDriver.class);
        Object connection = new Object();
        when(sources.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(driver, connection));
        when(sources.getMasked("source-1"))
                .thenReturn(Map.of("params", Map.of("project", "sales_project")));
        when(driver.getTreeNodes(connection, null))
                .thenReturn(List.of(node("orders", "TABLE", "orders")));

        AdminGrantScopeService.ScopeOptions result =
                new AdminGrantScopeService(sources).options(source("MAXCOMPUTE"));

        assertThat(result.namespaces()).extracting(
                AdminGrantScopeService.NamespaceOption::value)
                .containsExactly("sales_project");
        assertThat(result.tables()).containsExactly("sales_project.orders");
    }

    private static DataSource source(String dbType) {
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setDbType(dbType);
        return source;
    }

    private static TreeNode node(String name, String type, String path) {
        return TreeNode.of(path, name, type, path);
    }
}
