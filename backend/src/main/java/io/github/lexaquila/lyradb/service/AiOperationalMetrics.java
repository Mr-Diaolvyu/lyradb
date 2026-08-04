package io.github.lexaquila.lyradb.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程级 AI 运维指标。指标名来自固定枚举，不接收用户、工作空间或模型名，
 * 避免高基数标签和敏感信息泄露。
 */
@Component
public class AiOperationalMetrics {

    private final Instant startedAt = Instant.now();
    private final Map<Operation, Metric> values = new ConcurrentHashMap<>();

    public void record(Operation operation, boolean success, long durationMs) {
        Metric metric = values.computeIfAbsent(operation,
                ignored -> new Metric());
        metric.calls.incrementAndGet();
        if (!success) {
            metric.failures.incrementAndGet();
        }
        metric.durationMs.addAndGet(Math.max(0L, durationMs));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> operations = new LinkedHashMap<>();
        for (Operation operation : Operation.values()) {
            Metric metric = values.getOrDefault(operation, new Metric());
            long calls = metric.calls.get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("calls", calls);
            item.put("failures", metric.failures.get());
            item.put("durationMs", metric.durationMs.get());
            item.put("averageDurationMs", calls == 0
                    ? 0.0 : (double) metric.durationMs.get() / calls);
            operations.put(operation.wireName, Map.copyOf(item));
        }
        return Map.of(
                "scope", "PROCESS_LOCAL",
                "startedAt", startedAt,
                "operations", Map.copyOf(operations));
    }

    public enum Operation {
        PROVIDER_CHAT("provider.chat"),
        PROVIDER_TOOL_CHAT("provider.toolChat"),
        PROVIDER_EMBED("provider.embed"),
        PROVIDER_STREAM("provider.stream"),
        READ_AGENT_PLAN("readAgent.plan"),
        READ_AGENT_EXECUTE("readAgent.execute"),
        READ_AGENT_CANCEL("readAgent.cancel"),
        GATEWAY_REQUEST("gateway.request"),
        GATEWAY_RATE_LIMIT("gateway.rateLimit");

        private final String wireName;

        Operation(String wireName) {
            this.wireName = wireName;
        }
    }

    private static final class Metric {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong durationMs = new AtomicLong();
    }
}
