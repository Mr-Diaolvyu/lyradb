package io.github.lexaquila.lyradb.service;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭证加密服务单元测试
 */
class CredentialServiceTest {

    private CredentialService credentialService;

    @BeforeEach
    void setUp() {
        // 与生产一致的算法（application.yml: PBEWithMD5AndDES）+ 显式密钥
        StandardPBEStringEncryptor enc = new StandardPBEStringEncryptor();
        enc.setAlgorithm("PBEWithMD5AndDES");
        enc.setPassword("test-key");
        StringEncryptor encryptor = enc;
        credentialService = new CredentialService(encryptor);
    }

    @Test
    void encryptThenDecryptRoundTrip() {
        Map<String, Object> params = new HashMap<>();
        params.put("host", "localhost");
        params.put("password", "s3cret");

        Map<String, Object> encrypted = credentialService.encryptSensitiveFields(params);
        // 非敏感字段不变
        assertEquals("localhost", encrypted.get("host"));
        // 敏感字段已加密且与原值不同
        assertNotEquals("s3cret", encrypted.get("password"));

        Map<String, Object> decrypted = credentialService.decryptSensitiveFields(encrypted);
        assertEquals("s3cret", decrypted.get("password"));
    }

    @Test
    void maskReplacesSensitiveValue() {
        Map<String, Object> params = new HashMap<>();
        params.put("password", "s3cret");
        params.put("accessKeySecret", "AKSECRET");

        Map<String, Object> masked = credentialService.maskSensitiveFields(params);
        assertEquals("********", masked.get("password"));
        assertEquals("********", masked.get("accessKeySecret"));
    }

    @Test
    void maskedValueNotReEncrypted() {
        Map<String, Object> params = new HashMap<>();
        params.put("password", "********");

        Map<String, Object> encrypted = credentialService.encryptSensitiveFields(params);
        // 掩码值应原样保留，不被再次加密
        assertEquals("********", encrypted.get("password"));
    }

    @Test
    void decryptToleratesPlaintext() {
        // 已是明文的值解密失败时应保留原值（不抛异常）
        Map<String, Object> params = new HashMap<>();
        params.put("password", "plaintext-not-encrypted");

        Map<String, Object> decrypted = credentialService.decryptSensitiveFields(params);
        assertEquals("plaintext-not-encrypted", decrypted.get("password"));
    }

    @Test
    void handlesNullInput() {
        assertEquals(0, credentialService.encryptSensitiveFields(null).size());
        assertEquals(0, credentialService.decryptSensitiveFields(null).size());
        assertEquals(0, credentialService.maskSensitiveFields(null).size());
    }
}
