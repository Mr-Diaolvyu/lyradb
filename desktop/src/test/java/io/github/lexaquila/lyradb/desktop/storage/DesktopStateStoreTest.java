package io.github.lexaquila.lyradb.desktop.storage;

import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopStateStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPersistSecretsEncryptedAndRestoreThem() throws Exception {
        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            DesktopStateStore store = new DesktopStateStore(tempDirectory, vault);
            DesktopConnection connection = new DesktopConnection();
            connection.setName("测试 MySQL");
            connection.setDbType("MYSQL");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("host", "127.0.0.1");
            params.put("password", "db-password-value");
            params.put("accessKeySecret", "aliyun-secret-value");
            connection.setParams(params);
            DesktopConnection saved = store.saveConnection(connection);

            AiProfile ai = new AiProfile();
            ai.setApiKey("ai-api-key-value");
            store.saveAiProfile(ai);

            String raw = Files.readString(tempDirectory.resolve("desktop-state.json"));
            assertThat(raw)
                    .doesNotContain("db-password-value")
                    .doesNotContain("aliyun-secret-value")
                    .doesNotContain("ai-api-key-value")
                    .contains("lyradb-desktop-aes-gcm-v1");

            DesktopStateStore reopened = new DesktopStateStore(tempDirectory, vault);
            assertThat(reopened.findConnection(saved.getId()).orElseThrow()
                    .getParams().get("password")).isEqualTo("db-password-value");
            assertThat(reopened.findConnection(saved.getId()).orElseThrow()
                    .getParams().get("accessKeySecret")).isEqualTo("aliyun-secret-value");
            assertThat(reopened.getAiProfile().getApiKey()).isEqualTo("ai-api-key-value");
        }
    }

    @Test
    void shouldFailClosedWhenSensitiveValueIsPlaintext() throws Exception {
        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            Files.writeString(tempDirectory.resolve("desktop-state.json"), """
                    {
                      "formatVersion": 1,
                      "connections": [{
                        "id": "1",
                        "name": "unsafe",
                        "dbType": "MYSQL",
                        "params": {"password": "plaintext"},
                        "group": "",
                        "favorite": false
                      }]
                    }
                    """);
            assertThatThrownBy(() -> new DesktopStateStore(tempDirectory, vault))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未加密");
        }
    }
}
