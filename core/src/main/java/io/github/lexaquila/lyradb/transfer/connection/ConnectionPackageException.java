package io.github.lexaquila.lyradb.transfer.connection;

/**
 * 连接配置包读取或写入失败。
 *
 * <p>异常消息刻意不包含口令、密文、连接参数或原始 JSON 片段，调用方可以安全地
 * 展示给最终用户。若需要分支处理，应使用 {@link #getCode()}，不要解析消息。</p>
 */
public final class ConnectionPackageException extends Exception {

    public enum Code {
        INVALID_INPUT,
        FILE_TOO_LARGE,
        TOO_MANY_CONNECTIONS,
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_VERSION,
        INVALID_FIELD,
        MALFORMED_PACKAGE,
        INTEGRITY_FAILED,
        PASSWORD_REQUIRED,
        DECRYPTION_FAILED,
        IO_ERROR,
        CRYPTO_UNAVAILABLE
    }

    private final Code code;

    public ConnectionPackageException(Code code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    ConnectionPackageException(Code code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
