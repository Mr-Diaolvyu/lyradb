package io.github.lexaquila.lyradb.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopVaultTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldEncryptRoundTripAndRejectTampering() throws Exception {
        String secret = "sk-personal-secret-123";
        String encrypted;
        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            encrypted = vault.encrypt(secret);
            assertThat(encrypted).doesNotContain(secret);
            assertThat(DesktopVault.isEncrypted(encrypted)).isTrue();
            assertThat(vault.decrypt(encrypted)).isEqualTo(secret);

            String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
            assertThatThrownBy(() -> vault.decrypt(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("解密失败");
        }
        assertThat(Files.readString(tempDirectory.resolve("master.key")))
                .doesNotContain(secret);

        try (DesktopVault reopened = new DesktopVault(tempDirectory)) {
            assertThat(reopened.decrypt(encrypted)).isEqualTo(secret);
        }
    }

    @Test
    void shouldRejectDoubleEncryptionAndPlaintextDecryption() {
        try (DesktopVault vault = new DesktopVault(tempDirectory)) {
            String encrypted = vault.encrypt("password");
            assertThatThrownBy(() -> vault.encrypt(encrypted))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> vault.decrypt("plaintext"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
