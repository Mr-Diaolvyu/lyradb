package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ErDiagramMetadataLoaderTest {

    @Test
    void shouldUseMysqlCatalogAsDriverNamespace() {
        TreeNode node = TreeNode.of(
                "erp_base/orders", "orders", "TABLE", "erp_base/orders");
        node.getProperties().put("catalog", "erp_base");

        var choice = ErDiagramMetadataLoader.fromNode(
                node, "erp_base", "erp_base", "MYSQL");

        assertThat(choice.namespace()).isEqualTo("erp_base");
        assertThat(choice.displayNamespace()).isEqualTo("erp_base");
        assertThat(choice.label()).isEqualTo("erp_base.orders");
    }

    @Test
    void shouldUseSqlServerCatalogAndSchemaPath() {
        TreeNode node = TreeNode.of(
                "sales/dbo/orders", "orders", "TABLE", "sales/dbo/orders");
        node.getProperties().put("catalog", "sales");
        node.getProperties().put("schema", "dbo");

        var choice = ErDiagramMetadataLoader.fromNode(
                node, "sales", "sales", "MSSQL");

        assertThat(choice.namespace()).isEqualTo("sales/dbo");
        assertThat(choice.label()).isEqualTo("sales.dbo.orders");
    }

    @Test
    void shouldOnlyDrawRealForeignKeysAndReuseCache() throws Exception {
        ColumnMetadata orderId = column("id", true);
        ColumnMetadata customerId = column("customer_id", false);
        ColumnMetadata customerPk = column("id", true);
        TableConstraintMetadata foreignKey = new TableConstraintMetadata();
        foreignKey.setType("FOREIGN_KEY");
        foreignKey.setColumns(List.of("customer_id"));
        foreignKey.setReferencedTable("customers");
        foreignKey.setReferencedColumns(List.of("id"));
        AtomicInteger columnLoads = new AtomicInteger();
        Map<String, ErDiagramMetadataLoader.TableMetadata> cache =
                new HashMap<>();
        var source = new ErDiagramMetadataLoader.MetadataSource(
                (namespace, table) -> {
                    columnLoads.incrementAndGet();
                    return "orders".equals(table)
                            ? List.of(orderId, customerId)
                            : List.of(customerPk);
                },
                (namespace, table) -> "orders".equals(table)
                        ? List.of(foreignKey) : List.of());
        var choices = List.of(
                choice("erp_base", "orders"),
                choice("erp_base", "customers"));

        var first = ErDiagramMetadataLoader.load(source, choices, cache);
        var second = ErDiagramMetadataLoader.load(source, choices, cache);

        assertThat(first.tables()).hasSize(2);
        assertThat(first.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.from()).isEqualTo("erp_base.orders");
            assertThat(relation.to()).isEqualTo("erp_base.customers");
            assertThat(relation.fromColumn()).isEqualTo("customer_id");
            assertThat(relation.toColumn()).isEqualTo("id");
        });
        assertThat(second.relations()).hasSize(1);
        assertThat(columnLoads).hasValue(2);
    }

    @Test
    void shouldRenderCompleteOrdinaryScopeBeyondLegacyPickerLimit()
            throws Exception {
        List<ErDiagramMetadataLoader.TableChoice> choices =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(index -> choice(
                                "erp_base", "table_" + index))
                        .toList();
        var source = new ErDiagramMetadataLoader.MetadataSource(
                (namespace, table) -> List.of(),
                (namespace, table) -> List.of());

        var skeleton = ErDiagramMetadataLoader.skeleton(choices);
        var loaded = ErDiagramMetadataLoader.load(
                source, choices, new HashMap<>());

        assertThat(skeleton.tables()).hasSize(30);
        assertThat(loaded.tables()).hasSize(30);
        assertThat(loaded.truncated()).isFalse();
    }

    private static ErDiagramMetadataLoader.TableChoice choice(
            String namespace, String table) {
        return new ErDiagramMetadataLoader.TableChoice(
                table, namespace, namespace, "TABLE",
                namespace + "/" + table);
    }

    private static ColumnMetadata column(String name, boolean primary) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setTypeName("BIGINT");
        column.setPrimaryKey(primary);
        return column;
    }
}
