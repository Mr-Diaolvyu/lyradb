package io.github.lexaquila.lyradb.service;

/** Agent Gateway 固定窗口限流结果。 */
public class AiGatewayRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public AiGatewayRateLimitException(long retryAfterSeconds) {
        super("Agent Gateway 请求过多，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
