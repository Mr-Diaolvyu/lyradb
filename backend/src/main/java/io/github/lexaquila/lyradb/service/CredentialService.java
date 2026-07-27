package io.github.lexaquila.lyradb.service;

import org.jasypt.encryption.StringEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 凭证加密服务
 *
 * <p>
 * 使用Jasypt对密码、AccessKey Secret等敏感信息进行加密/解密。
 * 加密后的值存储在数据库中，传输到前端时进行掩码处理。
 * </p>
 *
 * <p>
 * 敏感字段自动识别：password, accessKeySecret, secret等字段名
 * </p>
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    /** 需要加密的字段名集合（不区分大小写匹配） */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "accesskeysecret", "secret", "access_key", "accesskey",
            "privatekey", "passphrase");

    /** 密码掩码值 */
    private static final String MASKED_VALUE = "********";

    private final StringEncryptor encryptor;

    public CredentialService(StringEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /**
     * 加密连接参数中的敏感字段
     *
     * @param params 原始连接参数
     * @return 加密后的连接参数（敏感字段已加密）
     */
    public Map<String, Object> encryptSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }

        Map<String, Object> encrypted = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : encrypted.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null) {
                String plainValue = entry.getValue().toString();
                if (!plainValue.isEmpty() && !plainValue.equals(MASKED_VALUE)) {
                    String encryptedValue = encryptor.encrypt(plainValue);
                    entry.setValue(encryptedValue);
                }
            }
        }
        return encrypted;
    }

    /**
     * 解密连接参数中的敏感字段
     *
     * @param params 加密的连接参数
     * @return 解密后的连接参数（敏感字段已解密）
     */
    public Map<String, Object> decryptSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }

        Map<String, Object> decrypted = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : decrypted.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null) {
                String encryptedValue = entry.getValue().toString();
                if (!encryptedValue.isEmpty() && !encryptedValue.equals(MASKED_VALUE)) {
                    try {
                        String plainValue = encryptor.decrypt(encryptedValue);
                        entry.setValue(plainValue);
                    } catch (Exception e) {
                        log.warn("解密字段 {} 失败，可能已经是明文: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
        }
        return decrypted;
    }

    /**
     * 掩码连接参数中的敏感字段（返回给前端时使用）
     */
    public Map<String, Object> maskSensitiveFields(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }

        Map<String, Object> masked = new java.util.HashMap<>(params);
        for (Map.Entry<String, Object> entry : masked.entrySet()) {
            if (isSensitiveField(entry.getKey()) && entry.getValue() != null) {
                String value = entry.getValue().toString();
                if (!value.isEmpty() && !value.equals(MASKED_VALUE)) {
                    entry.setValue(MASKED_VALUE);
                }
            }
        }
        return masked;
    }

    /**
     * 判断字段是否为敏感字段
     */
    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase();
        return SENSITIVE_FIELDS.stream().anyMatch(lower::contains);
    }
}
