

package io.github.lexaquila.lyradb.service;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 凭证加密服务。
 *
 * <p>新值使用 AES-256-GCM，每个值拥有独立随机盐和 96 位随机 IV；
 * 主密钥由部署密钥经 PBKDF2-HMAC-SHA256 派生。旧 Jasypt 密文只用于迁移读取，
 * 再保存时会自动转换为 v2 认证密文。</p>
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "accesskeysecret", "apikey", "secret", "access_key", "accesskey",
            "privatekey", "private_key", "token", "passphrase");

    public static final String MASKED_VALUE = "********";
    private static final String V2_PREFIX = "{aes-gcm-v2}";
    private static final String V1_PREFIX = "{enc-v1}";
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int MAX_VALUE_BYTES = 65_536;
    /** 仅用于读取历史开发/桌面数据，不参与任何新密文写入。 */
    private static final String LEGACY_DEFAULT_PASSWORD = "db-manager-default-key";

    private final StringEncryptor legacyEncryptor;
    private final StringEncryptor legacyDefaultEncryptor;
    private final AtomicBoolean legacyDefaultWarningLogged = new AtomicBoolean();
    private final char[] masterPassword;
    private final byte[] blindIndexKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialService(StringEncryptor legacyEncryptor,
            @Value("${jasypt.encryptor.password:}") String masterPassword) {
        if (masterPassword == null || masterPassword.isBlank()) {
            throw new IllegalStateException("凭证主密钥不能为空，请配置 JASYPT_PASSWORD");
        }
        this.legacyEncryptor = legacyEncryptor;
        StandardPBEStringEncryptor compatibilityEncryptor = new StandardPBEStringEncryptor();
        compatibilityEncryptor.setAlgorithm("PBEWithMD5AndDES");
        compatibilityEncryptor.setPassword(LEGACY_DEFAULT_PASSWORD);
        this.legacyDefaultEncryptor = compatibilityEncryptor;
        this.masterPassword = masterPassword.toCharArray();
        this.blindIndexKey = deriveBlindIndexKey(masterPassword);
    }

    public Map<String, Object> encryptSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        Map<String, Object> encrypted = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : encrypted.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null) {
                String value = entry.getValue().toString();
                if (!value.isEmpty() && !MASKED_VALUE.equals(value)) {
                    entry.setValue(encryptValue(value));
                }
            }
        }
        return encrypted;
    }

    public Map<String, Object> decryptSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        Map<String, Object> decrypted = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : decrypted.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null) {
                String value = entry.getValue().toString();
                if (!value.isEmpty() && !MASKED_VALUE.equals(value)) {
                    entry.setValue(decryptValue(value));
                }
            }
        }
        return decrypted;
    }

    public Map<String, Object> maskSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        Map<String, Object> masked = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : masked.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null
                    && !entry.getValue().toString().isEmpty()) {
                entry.setValue(MASKED_VALUE);
            }
        }
        return masked;
    }

    /**
     * 加密单个值。v2 值原样返回；旧 Jasypt 值先解密再迁移为 v2。
     */
    public String encryptValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.startsWith(V2_PREFIX)) {
            return value;
        }

        String plaintext = value;
        if (value.startsWith(V1_PREFIX)) {
            plaintext = decryptLegacy(value.substring(V1_PREFIX.length()));
        } else {
            String legacyPlaintext = tryDecryptLegacy(value);
            if (legacyPlaintext != null) {
                plaintext = legacyPlaintext;
            }
            // 两个旧解密器均失败时按历史明文兼容；新写入永远不会走此格式。
        }
        return encryptV2(plaintext);
    }

    /**
     * 解密单个值。带版本密文失败时明确抛错，绝不按明文降级。
     * 无前缀值仅用于兼容历史 Jasypt 密文或历史明文。
     */
    public String decryptValue(String value) {
        if (value == null || value.isEmpty() || MASKED_VALUE.equals(value)) {
            return value;
        }
        if (value.startsWith(V2_PREFIX)) {
            return decryptV2(value.substring(V2_PREFIX.length()));
        }
        if (value.startsWith(V1_PREFIX)) {
            return decryptLegacy(value.substring(V1_PREFIX.length()));
        }
        String legacyPlaintext = tryDecryptLegacy(value);
        if (legacyPlaintext != null) {
            return legacyPlaintext;
        }
        log.warn("读取到无版本前缀的历史明文凭证，建议重新保存以迁移到 AES-GCM");
        return value;
    }

    /**
     * 为需要精确检索但不能存明文的值生成 keyed blind index。
     */
    public String blindIndex(String purpose, String value) {
        if (purpose == null || purpose.isBlank() || value == null) {
            throw new IllegalArgumentException("blind index 参数不能为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(blindIndexKey, "HmacSHA256"));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成安全检索索引", exception);
        }
    }

    public boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_FIELDS.stream().anyMatch(lower::contains);
    }

    private String encryptV2(String plaintext) {
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("单个凭证值超过 64 KiB 限制");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(iv);
        try {
            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(V2_PREFIX.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(bytes);
            ByteBuffer payload = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
            payload.put(salt).put(iv).put(ciphertext);
            return V2_PREFIX + Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    private String decryptV2(String encoded) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length < SALT_BYTES + IV_BYTES + 16) {
                throw new IllegalArgumentException("密文长度无效");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - SALT_BYTES - IV_BYTES];
            buffer.get(salt).get(iv).get(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(V2_PREFIX.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("凭证密文认证失败，数据可能已被篡改", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("凭证密文格式无效或无法解密", e);
        }
    }

    private SecretKey deriveKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private String decryptLegacy(String ciphertext) {
        String plaintext = tryDecryptLegacy(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("旧版凭证密文无法解密");
        }
        return plaintext;
    }

    private String tryDecryptLegacy(String ciphertext) {
        try {
            return legacyEncryptor.decrypt(ciphertext);
        } catch (Exception primaryFailure) {
            try {
                String plaintext = legacyDefaultEncryptor.decrypt(ciphertext);
                if (legacyDefaultWarningLogged.compareAndSet(false, true)) {
                    log.warn("检测到使用历史默认密钥的凭证；下次保存时将迁移为随机主密钥的 AES-GCM 密文");
                }
                return plaintext;
            } catch (Exception compatibilityFailure) {
                return null;
            }
        }
    }

    private static byte[] deriveBlindIndexKey(String password) {
        byte[] domainSalt =
                "lyradb-blind-index-key-v1".getBytes(StandardCharsets.UTF_8);
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(), domainSalt,
                PBKDF2_ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("无法派生安全检索密钥", exception);
        } finally {
            spec.clearPassword();
        }
    }

    @PreDestroy
    void clearMasterPassword() {
        java.util.Arrays.fill(masterPassword, '\0');
        java.util.Arrays.fill(blindIndexKey, (byte) 0);
    }
}
