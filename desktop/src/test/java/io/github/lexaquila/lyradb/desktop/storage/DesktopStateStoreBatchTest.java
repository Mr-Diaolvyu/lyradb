package io.github.lexaquila.lyradb.desktop.storage;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopStateStoreBatchTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldPersistBatchUpsertOnceResolvedByCaller() {
        try (DesktopVault vault = new DesktopVault(temporaryDirectory)) {
            DesktopStateStore store =
                    new DesktopStateStore(temporaryDirectory, vault);
            DesktopConnection existing = connection("existing", "旧名称");
            store.saveConnection(existing);

            DesktopConnection replacement = connection("existing", "新名称");
            DesktopConnection added = connection("new-id", "新增连接");
            store.saveConnections(List.of(replacement, added));

            assertThat(store.listConnections())
                    .extracting(DesktopConnection::getName)
                    .containsExactlyInAnyOrder("新名称", "新增连接");
            DesktopStateStore reopened =
                    new DesktopStateStore(temporaryDirectory, vault);
            assertThat(reopened.listConnections())
                    .extracting(DesktopConnection::getName)
                    .containsExactlyInAnyOrder("新名称", "新增连接");
        }
    }

    @Test
    void duplicateIncomingIdsMustFailBeforeChangingState() {
        try (DesktopVault vault = new DesktopVault(temporaryDirectory)) {
            DesktopStateStore store =
                    new DesktopStateStore(temporaryDirectory, vault);
            store.saveConnection(connection("existing", "原连接"));

            assertThatThrownBy(() -> store.saveConnections(List.of(
                    connection("duplicate", "A"),
                    connection("duplicate", "B"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ID");
            assertThat(store.listConnections())
                    .extracting(DesktopConnection::getName)
                    .containsExactly("原连接");
        }
    }

    private static DesktopConnection connection(String id, String name) {
        DesktopConnection connection = new DesktopConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setDbType("MYSQL");
        return connection;
    }
}
