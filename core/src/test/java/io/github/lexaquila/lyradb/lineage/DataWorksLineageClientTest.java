package io.github.lexaquila.lyradb.lineage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataWorksLineageClientTest {

    @Test
    void rejectsNonOfficialCredentialEndpoint() {
        assertThatThrownBy(() -> DataWorksLineageClient.fromParameters(java.util.Map.of(
                "accessKeyId", "test-id", "accessKeySecret", "test-secret",
                "dataWorksRegionId", "cn-hangzhou", "dataWorksEndpoint", "attacker.example")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("官方");
    }

    @Test
    void shouldExploreRealUpstreamAndDownstreamEdges() throws Exception {
        String rootId = DataWorksLineageClient.tableEntityId(
                "demo_project", "dwd_order");
        var root = DataWorksLineageClient.parseEntity(rootId, null);
        var upstream = DataWorksLineageClient.parseEntity(
                DataWorksLineageClient.tableEntityId(
                        "demo_project", "ods_order"), null);
        var downstream = DataWorksLineageClient.parseEntity(
                DataWorksLineageClient.tableEntityId(
                        "demo_project", "ads_order"), null);
        DataWorksLineageClient.LineageApi api = (entityId, direction) -> {
            if (!rootId.equals(entityId)) {
                return List.of();
            }
            return direction == DataWorksLineageClient.QueryDirection.UPSTREAM
                    ? List.of(new DataWorksLineageClient.Edge(upstream, root))
                    : List.of(new DataWorksLineageClient.Edge(root, downstream));
        };

        var result = new DataWorksLineageClient(api).explore(
                List.of(rootId), DataWorksLineageClient.Direction.BOTH,
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
        String sourceId = DataWorksLineageClient.columnEntityId(
                "demo_project", "ods_order", "order_id");
        String targetId = DataWorksLineageClient.columnEntityId(
                "demo_project", "dwd_order", "order_id");
        var source = DataWorksLineageClient.parseEntity(sourceId, null);
        var target = DataWorksLineageClient.parseEntity(targetId, null);
        DataWorksLineageClient.LineageApi api = (entityId, direction) ->
                targetId.equals(entityId)
                        && direction
                        == DataWorksLineageClient.QueryDirection.UPSTREAM
                        ? List.of(new DataWorksLineageClient.Edge(
                                source, target))
                        : List.of();

        var result = new DataWorksLineageClient(api).explore(
                List.of(targetId),
                DataWorksLineageClient.Direction.UPSTREAM, 1, 10);

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
        assertThat(DataWorksLineageClient.ProbePolicy.fromValue("bad"))
                .isEqualTo(DataWorksLineageClient.ProbePolicy.MANUAL);
        assertThat(DataWorksLineageClient.ProbePolicy.fromValue(
                "every_30_minutes"))
                .isEqualTo(
                        DataWorksLineageClient.ProbePolicy.EVERY_30_MINUTES);
    }
}
