package io.github.lexaquila.lyradb.desktop.transfer;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.desktop.storage.DesktopStateStore;
import io.github.lexaquila.lyradb.desktop.storage.DesktopVault;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageCodec;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageEntry;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionTransferServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final ConnectionTransferService service =
            new ConnectionTransferService();
    private final ConnectionPackageCodec codec = new ConnectionPackageCodec();

    @Test
    void shouldExportWithoutCredentialsByDefaultPolicy() throws Exception {
        byte[] encoded = service.encode(
                List.of(connection()), CredentialExportPolicy.OMIT, new char[0]);

        String json = new String(encoded, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("db-secret-value");
        var result = codec.read(encoded, null);
        assertThat(result.credentialPolicy()).isEqualTo(CredentialExportPolicy.OMIT);
        assertThat(result.connections().get(0).credentials()).isEmpty();
        assertThat(result.connections().get(0).credentialKeys()).contains("password");
    }

    @Test
    void shouldExportPasswordEncryptedAndRestoreDesktopConnection() throws Exception {
        char[] password = "export-password".toCharArray();
        byte[] encoded = service.encode(List.of(connection()),
                CredentialExportPolicy.PASSWORD_ENCRYPTED, password);
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .doesNotContain("db-secret-value");
        Path source = temporaryDirectory.resolve("encrypted.lyradb-connections.json");
        Files.write(source, encoded);

        ConnectionTransferService.ImportBundle imported =
                service.read(source, "export-password".toCharArray());

        assertThat(imported.credentialPolicy())
                .isEqualTo(CredentialExportPolicy.PASSWORD_ENCRYPTED);
        assertThat(imported.desktopConnections()).singleElement()
                .satisfies(value -> assertThat(value.getParams().get("password"))
                        .isEqualTo("db-secret-value"));
    }

    @Test
    void shouldExportPlaintextOnlyWhenExplicitlySelected() throws Exception {
        byte[] encoded = service.encode(List.of(connection()),
                CredentialExportPolicy.PLAINTEXT, new char[0]);

        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .contains("db-secret-value");
        assertThat(codec.read(encoded, null).credentialPolicy())
                .isEqualTo(CredentialExportPolicy.PLAINTEXT);
    }

    @Test
    void omittedImportShouldExposeCredentialKeysAndEmptyValues() throws Exception {
        byte[] encoded = service.encode(
                List.of(connection()), CredentialExportPolicy.OMIT, new char[0]);
        Path source = temporaryDirectory.resolve("omitted.lyradb-connections.json");
        Files.write(source, encoded);

        ConnectionTransferService.ImportBundle imported =
                service.read(source, new char[0]);

        assertThat(imported.connections()).singleElement().satisfies(value -> {
            assertThat(value.credentialKeys()).contains("password");
            assertThat(value.connection().getParams().get("password")).isEqualTo("");
        });
    }

    @Test
    void tamperedPackageMustPreserveIntegrityFailureCode() throws Exception {
        byte[] encoded = service.encode(
                List.of(connection()), CredentialExportPolicy.OMIT, new char[0]);
        String tampered = new String(encoded, StandardCharsets.UTF_8)
                .replace("connection-1", "connection-2");
        Path source = temporaryDirectory.resolve(
                "tampered.lyradb-connections.json");
        Files.writeString(source, tampered, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.read(source, new char[0]))
                .isInstanceOfSatisfying(
                        ConnectionPackageException.class,
                        error -> assertThat(error.getCode()).isEqualTo(
                                ConnectionPackageException.Code.INTEGRITY_FAILED));
    }

    @Test
    void customCredentialClassificationMustSurviveImportPlanStoreAndExport()
            throws Exception {
        Map<String, Object> sourceParameters = new LinkedHashMap<>();
        sourceParameters.put("host", "127.0.0.1");
        sourceParameters.put("clientCredential", "custom-secret-value");
        ConnectionPackageEntry sourceEntry =
                ConnectionPackageEntry.fromMixedParameters(
                        "custom-credential", "自定义凭据", "MYSQL",
                        sourceParameters, java.util.Set.of("clientCredential"),
                        "", false);
        Path source = temporaryDirectory.resolve(
                "custom-credential.lyradb-connections.json");
        Files.write(source, codec.exportWithPlaintextCredentials(
                List.of(sourceEntry)));

        ConnectionTransferService.ImportBundle bundle =
                service.read(source, new char[0]);
        assertThat(bundle.desktopConnections()).singleElement()
                .satisfies(connection -> assertThat(
                        connection.getCredentialKeys())
                        .containsExactly("clientCredential"));

        ConnectionImportPlanner planner = new ConnectionImportPlanner();
        List<ConnectionImportPlanner.PreviewItem> preview =
                planner.preview(List.of(), bundle.desktopConnections());
        ConnectionImportPlanner.Resolution resolution =
                planner.resolve(List.of(), preview, List.of());

        try (DesktopVault vault = new DesktopVault(temporaryDirectory)) {
            DesktopStateStore store =
                    new DesktopStateStore(temporaryDirectory, vault);
            store.saveConnections(resolution.toSave());
            String state = Files.readString(
                    temporaryDirectory.resolve("desktop-state.json"));
            assertThat(state)
                    .doesNotContain("custom-secret-value")
                    .contains("clientCredential")
                    .contains("lyradb-desktop-aes-gcm-v1");

            DesktopConnection restored = new DesktopStateStore(
                    temporaryDirectory, vault).listConnections().get(0);
            assertThat(restored.getParams())
                    .containsEntry("clientCredential", "custom-secret-value");
            assertThat(restored.getCredentialKeys())
                    .containsExactly("clientCredential");

            byte[] exported = service.encode(List.of(restored),
                    CredentialExportPolicy.PLAINTEXT, new char[0]);
            ConnectionPackageEntry exportedEntry =
                    codec.read(exported, null).connections().get(0);
            assertThat(exportedEntry.credentials())
                    .containsEntry("clientCredential", "custom-secret-value");
            assertThat(exportedEntry.parameters())
                    .doesNotContainKey("clientCredential");
            assertThat(exportedEntry.credentialKeys())
                    .contains("clientCredential");
        }
    }

    private static DesktopConnection connection() {
        DesktopConnection connection = new DesktopConnection();
        connection.setId("connection-1");
        connection.setName("生产库");
        connection.setDbType("MYSQL");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("host", "127.0.0.1");
        params.put("port", 3306);
        params.put("password", "db-secret-value");
        connection.setParams(params);
        connection.setGroup("默认");
        connection.setFavorite(true);
        return connection;
    }
}
