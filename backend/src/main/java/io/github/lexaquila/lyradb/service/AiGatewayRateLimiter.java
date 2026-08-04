package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 每令牌、每分钟、按普通/高成本操作分桶的服务端限流。 */
@Service
public class AiGatewayRateLimiter {

    private final AppProperties properties;
    private final AiOperationalMetrics metrics;
    private final Map<BucketKey, AtomicInteger> buckets =
            new ConcurrentHashMap<>();

    public AiGatewayRateLimiter(
            AppProperties properties, AiOperationalMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public void requireAllowed(
            AiGatewayPrincipal principal, String operation,
            boolean expensive) {
        if (principal == null) {
            throw new IllegalArgumentException("Gateway 身份不能为空");
        }
        long minute = Instant.now().getEpochSecond() / 60;
        int limit = expensive
                ? properties.getAi().getGatewayExpensiveRequestsPerMinute()
                : properties.getAi().getGatewayRequestsPerMinute();
        BucketKey key = new BucketKey(
                principal.tokenId(), operation, expensive, minute);
        int current = buckets.computeIfAbsent(
                key, ignored -> new AtomicInteger()).incrementAndGet();
        if (current > limit) {
            metrics.record(AiOperationalMetrics.Operation.GATEWAY_RATE_LIMIT,
                    false, 0L);
            long retry = 60 - Math.floorMod(
                    Instant.now().getEpochSecond(), 60);
            throw new AiGatewayRateLimitException(retry);
        }
        metrics.record(AiOperationalMetrics.Operation.GATEWAY_REQUEST,
                true, 0L);
    }

    @Scheduled(fixedDelay = 300_000L)
    void removeExpiredBuckets() {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        buckets.keySet().removeIf(key -> key.minute() < currentMinute - 1);
    }

    int bucketCount() {
        return buckets.size();
    }

    private record BucketKey(
            String tokenId, String operation,
            boolean expensive, long minute) {
    }
}
