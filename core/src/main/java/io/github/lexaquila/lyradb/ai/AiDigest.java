package io.github.lexaquila.lyradb.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** AI 领域对象使用的稳定 SHA-256 摘要工具。 */
public final class AiDigest {

    private AiDigest() {
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("摘要内容不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
