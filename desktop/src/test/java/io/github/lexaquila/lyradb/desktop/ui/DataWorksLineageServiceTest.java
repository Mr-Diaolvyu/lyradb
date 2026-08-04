package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataWorksLineageServiceTest {

    @Test
    void shouldExploreRealUpstreamAndDownstreamEdges() throws Exception {
        String rootId = DataWorksLineageService.tableEntityId(
                "demo_project", "dwd_order");
        var root = DataWorksLineageService.parseEntity(rootId, null);
        var upstream = DataWorksLineageService.parseEntity(
                DataWorksLineageService.tableEntityId(
                        "demo_project", "ods_order"), null);
        var downstream = DataWorksLineageService.parseEntity(
                DataWorksLineageService.tableEntityId(
                        "demo_project", "ads_order"), null);
        DataWorksLineageService.LineageApi api = (entityId, direction) -> {
            if (!rootId.equals(entityId)) {
                return List.of();
            }
            return direction == DataWorksLineageService.QueryDirection.UPSTREAM
                    ? List.of(new DataWorksLineageService.Edge(upstream, root))
                    : List.of(new DataWorksLineageService.Edge(root, downstream));
        };

        var result = new DataWorksLineageService(api).explore(
                List.of(rootId), DataWorksLineageService.Direction.BOTH,
                2, 20);

        assertThat(result.graph().tables()).extracting("name")
                .containsExactlyInAnyOrder(
                        "ods_order", "dwd_order", "ads_order");
        assertThat(result.graph().relations()).hasSize(2);
        assertThat(result.edgeCount()).isEqualTo(2);
        assertThat(result.graph().truncated()).isFalse();
    }

    @Test
    void shouldBuildColumnLineageGraphWithPhysicalColumns() throws Exception {
        String sourceId = DataWorksLineageService.columnEntityId(
                "demo_project", "ods_order", "order_id");
        String targetId = DataWorksLineageService.columnEntityId(
                "demo_project", "dwd_order", "order_id");
        var source = DataWorksLineageService.parseEntity(sourceId, null);
        var target = DataWorksLineageService.parseEntity(targetId, null);
        DataWorksLineageService.LineageApi api = (entityId, direction) ->
                targetId.equals(entityId)
                        && direction
                        == DataWorksLineageService.QueryDirection.UPSTREAM
                        ? List.of(new DataWorksLineageService.Edge(
                                source, target))
                        : List.of();

        var result = new DataWorksLineageService(api).explore(
                List.of(targetId),
                DataWorksLineageService.Direction.UPSTREAM, 1, 10);

        assertThat(result.graph().tables()).hasSize(2);
        assertThat(result.graph().tables())
                .allSatisfy(table -> assertThat(table.columns())
                        .singleElement()
                        .extracting("name")
                        .isEqualTo("order_id"));
        assertThat(result.graph().relations()).singleElement()
                .satisfies(relation -> {
                    assertThat(relation.fromColumn()).isEqualTo("order_id");
                    assertThat(relation.toColumn()).isEqualTo("order_id");
                });
    }

    @Test
    void shouldUseSafeManualProbePolicyForUnknownValue() {
        assertThat(DataWorksLineageService.ProbePolicy.fromValue("bad"))
                .isEqualTo(DataWorksLineageService.ProbePolicy.MANUAL);
        assertThat(DataWorksLineageService.ProbePolicy.fromValue(
                "every_30_minutes"))
                .isEqualTo(
                        DataWorksLineageService.ProbePolicy.EVERY_30_MINUTES);
    }
}
