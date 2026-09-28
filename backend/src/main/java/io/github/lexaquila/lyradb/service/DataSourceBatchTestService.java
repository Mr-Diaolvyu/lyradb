package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 最多两个并行连接测试；任务进度只保留在内存，单项结果持久化在数据源。 */
@Service
public class DataSourceBatchTestService {
    private static final Logger log = LoggerFactory.getLogger(DataSourceBatchTestService.class);
    private final DataSourceService dataSourceService;
    private final AuditService auditService;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private final Map<String, BatchJob> jobs = new LinkedHashMap<>();

    public DataSourceBatchTestService(DataSourceService dataSourceService,
                                      AuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.auditService = auditService;
    }

    public synchronized Map<String, Object> start(String workspaceId, String userId,
                                                    String username, List<DataSource> sources) {
        jobs.values().removeIf(job -> !"RUNNING".equals(job.state)
                && Duration.between(job.createdAt, Instant.now()).toHours() >= 1);
        while (jobs.size() >= 10) {
            String finished = jobs.values().stream()
                    .filter(job -> !"RUNNING".equals(job.state))
                    .map(job -> job.id).findFirst().orElse(null);
            if (finished == null) break;
            jobs.remove(finished);
        }
        if (jobs.size() >= 10 || executor.getQueue().remainingCapacity() == 0) {
            throw new IllegalStateException("批量检测任务已满，请稍后重试");
        }
        BatchJob job = new BatchJob(UUID.randomUUID().toString(), workspaceId, userId,
                Instant.now(), sources);
        jobs.put(job.id, job);
        executor.execute(() -> run(job, username));
        return view(job);
    }

    public synchronized Map<String, Object> get(String id, String workspaceId, String userId) {
        BatchJob job = jobs.get(id);
        if (job == null || !job.workspaceId.equals(workspaceId)
                || !job.userId.equals(userId)) {
            throw new IllegalArgumentException("批量检测任务不存在或无权访问");
        }
        return view(job);
    }

    private void run(BatchJob job, String username) {
        for (BatchItem item : job.items) {
            item.state = "RUNNING";
            try {
                if (!job.workspaceId.equals(dataSourceService.getEntity(
                        item.source.getId()).getWorkspaceId())) {
                    throw new IllegalStateException("数据源已移出当前工作空间");
                }
                Map<String, Object> result = dataSourceService.test(item.source.getId());
                item.result = result;
                item.state = "DONE";
                boolean success = Boolean.TRUE.equals(result.get("success"));
                try {
                    auditService.record(job.workspaceId, job.userId, username, "DS_ADMIN",
                            item.source.getId(), item.source.getDisplayName(), item.source.getDbType(),
                            "DATA_SOURCE_TEST", null, 0, 0,
                            result.get("elapsedMs") instanceof Number elapsed ? elapsed.longValue() : 0,
                            success, success ? null : String.valueOf(result.get("message")));
                } catch (Exception auditFailure) {
                    // 检测结果已经写入数据源，审计故障不能把检测结果覆盖成未知。
                    log.error("批量检测结果审计写入失败: {}", item.source.getId(), auditFailure);
                }
            } catch (Exception exception) {
                item.state = "ERROR";
                item.result = Map.of("success", false,
                        "message", "检测执行失败，请查看服务端审计或日志");
                log.warn("批量检测任务项失败: {} - {}",
                        item.source.getId(), exception.getClass().getSimpleName());
            }
        }
        job.state = "DONE";
    }

    private Map<String, Object> view(BatchJob job) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BatchItem item : job.items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dataSourceId", item.source.getId());
            row.put("displayName", item.source.getDisplayName());
            row.put("state", item.state);
            row.put("result", item.result);
            items.add(row);
        }
        return Map.of("id", job.id, "state", job.state, "items", items);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class BatchJob {
        private final String id;
        private final String workspaceId;
        private final String userId;
        private final Instant createdAt;
        private final List<BatchItem> items;
        private volatile String state = "RUNNING";

        private BatchJob(String id, String workspaceId, String userId,
                         Instant createdAt, List<DataSource> sources) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.userId = userId;
            this.createdAt = createdAt;
            this.items = sources.stream().map(BatchItem::new).toList();
        }
    }

    private static final class BatchItem {
        private final DataSource source;
        private volatile String state = "PENDING";
        private volatile Map<String, Object> result;

        private BatchItem(DataSource source) {
            this.source = source;
        }
    }
}
