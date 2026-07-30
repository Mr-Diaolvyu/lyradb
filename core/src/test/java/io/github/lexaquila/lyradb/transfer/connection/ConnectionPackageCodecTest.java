package io.github.lexaquila.lyradb.transfer.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionPackageCodecTest {

    private final ConnectionPackageCodec codec = new ConnectionPackageCodec();

    @Test
    void shouldOmitAllTopLevelCredentialValues() throws Exception {
        ConnectionPackageEntry entry = sampleEntry();

        byte[] exported = codec.exportWithoutCredentials(List.of(entry));
        String json = new String(exported, StandardCharsets.UTF_8);
        ConnectionPackageReadResult result = codec.read(exported, null);

        assertThat(json).doesNotContain("db-secret");
        assertThat(result.credentialPolicy()).isEqualTo(CredentialExportPolicy.OMIT);
        assertThat(result.risk().hasOmittedCredentials()).isTrue();
        ConnectionPackageEntry restored = result.connections().get(0);
        assertThat(restored.credentials()).isEmpty();
        assertThat(restored.credentialKeys()).contains("password");
        Map<?, ?> options = (Map<?, ?>) restored.parameters().get("options");
        assertThat(options.get("ssl")).isEqualTo(true);
    }

    @Test
    void shouldMarkPlaintextExportAsHighRisk() throws Exception {
        byte[] exported = codec.exportWithPlaintextCredentials(
                List.of(sampleEntry()));
        String json = new String(exported, StandardCharsets.UTF_8);
        ConnectionPackageReadResult result = codec.read(exported, null);

        assertThat(json)
                .contains("\"credentialMode\" : \"PLAINTEXT\"", "db-secret",
                        "PLAINTEXT_DATABASE_CREDENTIALS");
        assertThat(json).contains("\"createdAt\"", "\"sourceVersion\"",
                "\"integrity\"", "\"SHA-256\"");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.sourceVersion()).isEqualTo("3.1.1");
        assertThat(result.credentialPolicy()).isEqualTo(CredentialExportPolicy.PLAINTEXT);
        assertThat(result.risk().hasPlaintextDatabaseCredentials()).isTrue();
        assertThat(result.connections().get(0).credentials())
                .containsEntry("password", "db-secret");
    }

    @Test
    void shouldEncryptAndDecryptWithUserPassword() throws Exception {
        char[] password = "correct horse".toCharArray();
        byte[] exported = codec.exportWithPassword(List.of(sampleEntry()), password);
        String json = new String(exported, StandardCharsets.UTF_8);
        ConnectionPackageReadResult result = codec.read(
                exported, "correct horse".toCharArray());

        assertThat(json).doesNotContain("db-secret");
        assertThat(json).contains("AES-256-GCM", "PBKDF2-HMAC-SHA256");
        assertThat(result.credentialPolicy())
                .isEqualTo(CredentialExportPolicy.PASSWORD_ENCRYPTED);
        assertThat(result.connections().get(0).credentials())
                .containsEntry("password", "db-secret");
    }

    @Test
    void shouldRejectWrongPasswordWithoutLeakingSecrets() throws Exception {
        byte[] exported = codec.exportWithPassword(
                List.of(sampleEntry()), "correct horse".toCharArray());

        assertPackageError(
                () -> codec.read(exported, "wrong password".toCharArray()),
                ConnectionPackageException.Code.DECRYPTION_FAILED);
    }

    @Test
    void shouldRejectTamperedCiphertext() throws Exception {
        byte[] exported = codec.exportWithPassword(
                List.of(sampleEntry()), "correct horse".toCharArray());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(exported);
        String encrypted = root.path("encryptedPayload").asText();
        char replacement = encrypted.charAt(0) == 'A' ? 'B' : 'A';
        root.put("encryptedPayload", replacement + encrypted.substring(1));

        assertPackageError(
                () -> codec.read(mapper.writeValueAsBytes(root),
                        "correct horse".toCharArray()),
                ConnectionPackageException.Code.INTEGRITY_FAILED);
    }

    @Test
    void shouldRejectTamperedPlaintextPackage() throws Exception {
        byte[] exported = codec.exportWithPlaintextCredentials(
                List.of(sampleEntry()));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(exported);
        ((ObjectNode) root.withArray("connections").get(0))
                .put("displayName", "tampered");

        assertPackageError(
                () -> codec.read(mapper.writeValueAsBytes(root), null),
                ConnectionPackageException.Code.INTEGRITY_FAILED);
    }

    @Test
    void shouldRejectApplicationLevelKeys() {
        ConnectionPackageEntry unsafe = new ConnectionPackageEntry(
                "unsafe", "unsafe", "MYSQL", "MySQL",
                Map.of("host", "localhost"),
                Map.of("openAiApiKey", "never-export"),
                Set.of("openAiApiKey"), "", "", "", List.of(),
                false, 0, false);

        assertPackageError(
                () -> codec.exportWithPlaintextCredentials(List.of(unsafe)),
                ConnectionPackageException.Code.INVALID_FIELD);
    }

    @Test
    void shouldRejectNestedDefaultCredentialInPlaintextMode() {
        ConnectionPackageEntry unsafe = entryWithNestedParameter(
                "password", "nested-secret", Set.of());

        assertPackageError(
                () -> codec.exportWithPlaintextCredentials(List.of(unsafe)),
                ConnectionPackageException.Code.INVALID_FIELD);
    }

    @Test
    void shouldRejectDeepCustomCredentialInPasswordEncryptedMode() {
        ConnectionPackageEntry unsafe = entryWithNestedParameter(
                "clientCredential", "nested-secret",
                Set.of("client_credential"));

        assertPackageError(
                () -> codec.exportWithPassword(
                        List.of(unsafe), "correct horse".toCharArray()),
                ConnectionPackageException.Code.INVALID_FIELD);
    }

    @Test
    void shouldAllowCustomCredentialOnlyInTopLevelCredentials()
            throws Exception {
        ConnectionPackageEntry safe = new ConnectionPackageEntry(
                "custom", "custom", "MYSQL", "MySQL",
                Map.of("host", "localhost"),
                Map.of("clientCredential", "db-secret"),
                Set.of("client_credential"), "", "", "", List.of(),
                false, 0, false);

        byte[] exported = codec.exportWithPassword(
                List.of(safe), "correct horse".toCharArray());
        ConnectionPackageReadResult result = codec.read(
                exported, "correct horse".toCharArray());

        assertThat(result.connections().get(0).credentials())
                .containsEntry("clientCredential", "db-secret");
    }

    @Test
    void shouldEnforceConnectionAndFileLimits() {
        ConnectionPackageCodec oneConnection = new ConnectionPackageCodec(
                new ConnectionPackageLimits(10_000, 1, 32, 100, 8, 4_096));
        assertPackageError(
                () -> oneConnection.exportWithoutCredentials(
                        List.of(sampleEntry(), secondEntry())),
                ConnectionPackageException.Code.TOO_MANY_CONNECTIONS);

        ConnectionPackageCodec tinyFile = new ConnectionPackageCodec(
                new ConnectionPackageLimits(64, 10, 32, 100, 8, 4_096));
        assertPackageError(
                () -> tinyFile.read(new byte[65], null),
                ConnectionPackageException.Code.FILE_TOO_LARGE);
    }

    @Test
    void shouldRequireAtLeastEightPasswordCharacters() {
        assertPackageError(
                () -> codec.exportWithPassword(
                        List.of(sampleEntry()), "short".toCharArray()),
                ConnectionPackageException.Code.PASSWORD_REQUIRED);
    }

    private static ConnectionPackageEntry sampleEntry() {
        return new ConnectionPackageEntry(
                "primary", "main", "MYSQL", "MySQL",
                Map.of("host", "localhost", "port", 3306,
                        "options", Map.of("ssl", true)),
                Map.of("password", "db-secret"), Set.of("password"),
                "local", "", "", List.of("test"), true, 1, false);
    }

    private static ConnectionPackageEntry entryWithNestedParameter(
            String key, String value, Set<String> credentialKeys) {
        return new ConnectionPackageEntry(
                "nested", "nested", "MYSQL", "MySQL",
                Map.of("options", List.of(
                        Map.of("inner", List.of(Map.of(key, value))))),
                Map.of(), credentialKeys, "", "", "", List.of(),
                false, 0, false);
    }

    private static ConnectionPackageEntry secondEntry() {
        return new ConnectionPackageEntry(
                "secondary", "backup", "POSTGRESQL", "PostgreSQL",
                Map.of("host", "localhost"), Map.of(), Set.of(),
                "", "", "", List.of(), false, 0, false);
    }

    private static void assertPackageError(
            ThrowingAction action, ConnectionPackageException.Code expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ConnectionPackageException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
