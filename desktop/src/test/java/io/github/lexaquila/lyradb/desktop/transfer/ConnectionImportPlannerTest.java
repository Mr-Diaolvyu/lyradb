package io.github.lexaquila.lyradb.desktop.transfer;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionImportPlannerTest {

    private final ConnectionImportPlanner planner = new ConnectionImportPlanner();

    @Test
    void conflictingConnectionMustDefaultToSkip() {
        DesktopConnection existing = connection("existing-id", "订单库");
        DesktopConnection incoming = connection("incoming-id", "订单库");

        var preview = planner.preview(List.of(existing), List.of(incoming));
        var resolution = planner.resolve(List.of(existing), preview, List.of());

        assertThat(preview).singleElement().satisfies(item -> {
            assertThat(item.conflictKind())
                    .isEqualTo(ConnectionImportPlanner.ConflictKind.EXISTING);
            assertThat(item.defaultAction())
                    .isEqualTo(ConnectionImportPlanner.Action.SKIP);
        });
        assertThat(resolution.toSave()).isEmpty();
        assertThat(resolution.skippedCount()).isEqualTo(1);
    }

    @Test
    void explicitOverwriteMustReuseExistingId() {
        DesktopConnection existing = connection("existing-id", "订单库");
        DesktopConnection incoming = connection("incoming-id", "订单库");
        var preview = planner.preview(List.of(existing), List.of(incoming));

        var resolution = planner.resolve(List.of(existing), preview,
                List.of(new ConnectionImportPlanner.Decision(
                        0, ConnectionImportPlanner.Action.OVERWRITE, "")));

        assertThat(resolution.toSave()).singleElement()
                .satisfies(value -> assertThat(value.getId())
                        .isEqualTo("existing-id"));
        assertThat(resolution.overwrittenIds()).containsExactly("existing-id");
        assertThat(resolution.overwrittenCount()).isEqualTo(1);
    }

    @Test
    void explicitRenameMustCreateNewIdAndName() {
        DesktopConnection existing = connection("existing-id", "订单库");
        DesktopConnection incoming = connection("incoming-id", "订单库");
        var preview = planner.preview(List.of(existing), List.of(incoming));

        var resolution = planner.resolve(List.of(existing), preview,
                List.of(new ConnectionImportPlanner.Decision(
                        0, ConnectionImportPlanner.Action.RENAME, "订单库副本")));

        assertThat(resolution.toSave()).singleElement().satisfies(value -> {
            assertThat(value.getName()).isEqualTo("订单库副本");
            assertThat(value.getId()).isNotEqualTo("incoming-id")
                    .isNotEqualTo("existing-id");
        });
        assertThat(resolution.renamedCount()).isEqualTo(1);
    }

    @Test
    void blankIdsMustNotConflictAndMustReceiveUniqueIds() {
        List<DesktopConnection> incoming = List.of(
                connection("", "订单库"),
                connection("", "会员库"),
                connection("", "报表库"));

        var preview = planner.preview(List.of(), incoming);
        var resolution = planner.resolve(List.of(), preview, List.of());

        assertThat(preview)
                .extracting(ConnectionImportPlanner.PreviewItem::conflictKind)
                .containsOnly(ConnectionImportPlanner.ConflictKind.NONE);
        assertThat(preview)
                .extracting(ConnectionImportPlanner.PreviewItem::defaultAction)
                .containsOnly(ConnectionImportPlanner.Action.IMPORT);
        assertThat(resolution.toSave()).hasSize(3);
        assertThat(resolution.toSave().stream()
                .map(DesktopConnection::getId).toList())
                .allMatch(id -> id != null && !id.isBlank())
                .doesNotHaveDuplicates();
        assertThat(resolution.importedCount()).isEqualTo(3);
    }

    @Test
    void duplicateNamesMustStillConflictWhenIdsAreBlank() {
        List<DesktopConnection> incoming = List.of(
                connection("", "订单库"),
                connection("", "订单库"));

        var preview = planner.preview(List.of(), incoming);

        assertThat(preview.get(0).conflictKind())
                .isEqualTo(ConnectionImportPlanner.ConflictKind.NONE);
        assertThat(preview.get(1).conflictKind())
                .isEqualTo(ConnectionImportPlanner.ConflictKind.FILE_DUPLICATE);
        assertThat(preview.get(1).defaultAction())
                .isEqualTo(ConnectionImportPlanner.Action.SKIP);
    }

    private static DesktopConnection connection(String id, String name) {
        DesktopConnection connection = new DesktopConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setDbType("MYSQL");
        return connection;
    }
}
