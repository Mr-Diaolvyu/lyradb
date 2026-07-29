package io.github.lexaquila.lyradb.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.Test;

class CredentialLegacyDefaultMigrationTest {

    @Test
    void 桌面随机主密钥可读取旧默认密钥并迁移为新格式() {
        StandardPBEStringEncryptor historical = encryptor("db-manager-default-key");
        String oldCiphertext = historical.encrypt("legacy-desktop-secret");

        StandardPBEStringEncryptor current = encryptor("new-random-installation-key");
        CredentialService service =
                new CredentialService(current, "new-random-installation-key");

        assertThat(service.decryptValue(oldCiphertext))
                .isEqualTo("legacy-desktop-secret");

        String migrated = service.encryptValue(oldCiphertext);
        assertThat(migrated).startsWith("{aes-gcm-v2}");
        assertThat(migrated).isNotEqualTo(oldCiphertext);
        assertThat(service.decryptValue(migrated))
                .isEqualTo("legacy-desktop-secret");
    }

    private static StandardPBEStringEncryptor encryptor(String password) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm("PBEWithMD5AndDES");
        encryptor.setPassword(password);
        return encryptor;
    }
}
