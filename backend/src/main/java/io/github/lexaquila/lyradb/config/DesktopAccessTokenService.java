
package io.github.lexaquila.lyradb.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 桌面版一次性本机访问令牌。
 *
 * <p>内存中仅保存令牌摘要；每次从托盘打开浏览器时签发新令牌并使旧令牌失效，
 * 成功消费后立即清除，避免 URL 被重放。</p>
 */
@Component
@ConditionalOnProperty(name = "app.desktop.tray-enabled", havingValue = "true")
public class DesktopAccessTokenService {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicReference<byte[]> pendingTokenDigest = new AtomicReference<>();

    /**
     * 签发新的高熵一次性令牌。调用方只能把它用于本机浏览器启动 URL，不得记录日志。
     */
    public String issueToken() {
        String token = issueSessionProof();
        pendingTokenDigest.set(digest(token));
        return token;
    }

    /**
     * 恒定时间校验并原子消费令牌。并发请求中最多一个请求成功。
     */
    public boolean consume(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        byte[] expected = pendingTokenDigest.get();
        if (expected == null || !MessageDigest.isEqual(expected, digest(candidate))) {
            return false;
        }
        return pendingTokenDigest.compareAndSet(expected, null);
    }

    /** 为已交换 bootstrap 的浏览器会话签发只存在于 URL fragment/sessionStorage 的证明。 */
    public String issueSessionProof() {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
