package io.github.lexaquila.lyradb.metadata.snapshot;

import java.util.Objects;

/**
 * 与具体模型分词器无关的近似 Token 估算器。
 *
 * <p>按 UTF-8 字节数除以 3 向上取整。该估算会比常见英文 4 字符/Token
 * 更保守，同时中文通常为 3 个 UTF-8 字节，不会被简单的“字符数/4”严重
 * 低估。结果只用于预算提示，不等同于服务商计费值。</p>
 */
public final class ApproximateTokenEstimator {

    public static final int UTF8_BYTES_PER_ESTIMATED_TOKEN = 3;
    public static final long MAX_INPUT_UTF8_BYTES = 64L * 1024L * 1024L;

    private ApproximateTokenEstimator() {
    }

    public static long estimate(CharSequence text) {
        Objects.requireNonNull(text, "text");
        long bytes = utf8Length(text);
        return estimateUtf8Bytes(bytes);
    }

    public static long estimateUtf8(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        return estimateUtf8Bytes(utf8.length);
    }

    public static long estimateUtf8Bytes(long byteLength) {
        if (byteLength < 0 || byteLength > MAX_INPUT_UTF8_BYTES) {
            throw new IllegalArgumentException("Token 估算输入超过允许上限");
        }
        if (byteLength == 0) {
            return 0;
        }
        return (byteLength + UTF8_BYTES_PER_ESTIMATED_TOKEN - 1)
                / UTF8_BYTES_PER_ESTIMATED_TOKEN;
    }

    private static long utf8Length(CharSequence value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7F) {
                bytes++;
            } else if (character <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_INPUT_UTF8_BYTES) {
                throw new IllegalArgumentException("Token 估算输入超过允许上限");
            }
        }
        return bytes;
    }
}
