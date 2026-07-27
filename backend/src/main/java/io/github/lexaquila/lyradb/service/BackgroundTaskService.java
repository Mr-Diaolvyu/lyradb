package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.BackgroundTask;
import io.github.lexaquila.lyradb.model.dto.QueryResult;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 后台查询任务服务（迭代二 E1）
 *
 * <p>
 * 长查询转入后台线程池执行（4 并发），任务与结果均驻留内存：
 * </p>
 * <ul>
 * <li>任务注册表上限 {@value #MAX_TASKS} 个，超限先淘汰最早的已终态任务</li>
 * <li>结果暂存区上限 {@value #MAX_RESULTS} 个，先进先出淘汰（淘汰后不可回看）</li>
 * <li>状态变更通过 {@code /ws/tasks} WebSocket 广播（RUNNING/DONE/ERROR/CANCELLED）</li>
 * </ul>
 *
 * <p>
 * 执行复用 {@link QueryService#executeQuery}，SQL 审核、历史记录逻辑与前台一致；
 * 命中审核拦截且未 force 时任务直接置为 ERROR（前端应先走确认流再提交）。
 * </p>
 */
@Service
public class BackgroundTaskService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskService.class);

    private static final int MAX_TASKS = 50;
    private static final int MAX_RESULTS = 20;

    private final QueryService queryService;
    private final TaskWebSocketHandler taskWebSocketHandler;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bg-query");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, QueryResult> results = new ConcurrentHashMap<>();
    /** 结果暂存的 FIFO 淘汰队列（仅存任务 ID） */
    private final Deque<String> resultOrder = new ArrayDeque<>();

    public BackgroundTaskService(QueryService queryService, TaskWebSocketHandler taskWebSocketHandler) {
        this.queryService = queryService;
        this.taskWebSocketHandler = taskWebSocketHandler;
    }

    /**
     * 提交后台查询任务
     *
     * @param force 已经过前端"仍要执行"确认（审核拦截逃生门），与前台语义一致
     * @return 新任务（RUNNING 态）
     */
    public BackgroundTask submit(String connectionId, String connectionName,
            String sql, String defaultDatabase, boolean force) {
        evictTasksIfNeeded();

        BackgroundTask task = new BackgroundTask();
        task.setId(UUID.randomUUID().toString());
        task.setConnectionId(connectionId);
        task.setConnectionName(connectionName);
        task.setSql(sql);
        tasks.put(task.getId(), task);

        Future<?> future = executor.submit(() -> run(task, defaultDatabase, force));
        task.setFuture(future);
        log.info("后台任务已提交: {} (connectionId={})", task.getId(), connectionId);
        return task;
    }

    /** 任务列表（按提交时间倒序） */
    public List<BackgroundTask> list() {
        List<BackgroundTask> list = new ArrayList<>(tasks.values());
        list.sort(Comparator.comparing(BackgroundTask::getSubmittedAt).reversed());
        return list;
    }

    /** 回取任务结果（DONE 且未被暂存区淘汰时可用） */
    public QueryResult getResult(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在: " + taskId);
        }
        QueryResult result = results.get(taskId);
        if (result == null) {
            throw new RuntimeException("结果已失效（暂存区已淘汰），请重新执行");
        }
        return result;
    }

    /** 取消运行中任务：中断线程 + 尝试 Statement.cancel */
    public boolean cancel(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null || !"RUNNING".equals(task.getStatus())) {
            return false;
        }
        if (task.getFuture() != null) {
            task.getFuture().cancel(true);
        }
        try {
            queryService.cancelQuery(task.getConnectionId());
        } catch (Exception e) {
            log.warn("取消后台任务的语句失败: {}", e.getMessage());
        }
        finish(task, "CANCELLED", null, "已取消");
        return true;
    }

    /** 删除任务记录（终态任务），连带清理暂存结果 */
    public void remove(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task != null && "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务运行中，请先取消");
        }
        tasks.remove(taskId);
        synchronized (resultOrder) {
            results.remove(taskId);
            resultOrder.remove(taskId);
        }
    }

    private void run(BackgroundTask task, String defaultDatabase, boolean force) {
        long start = System.currentTimeMillis();
        try {
            QueryResult result = queryService.executeQuery(
                    task.getConnectionId(), task.getSql(), defaultDatabase, force);
            task.setElapsedMs(System.currentTimeMillis() - start);
            if (result.isReviewBlocked()) {
                finish(task, "ERROR", null, "命中SQL审核拦截，请在编辑器中确认后重新提交");
                return;
            }
            stashResult(task.getId(), result);
            task.setTotalRows(result.getTotalRows());
            task.setResultAvailable(true);
            finish(task, "DONE", result, null);
        } catch (Exception e) {
            task.setElapsedMs(System.currentTimeMillis() - start);
            // 取消触发的中断不覆盖 CANCELLED 态
            if (!"CANCELLED".equals(task.getStatus())) {
                finish(task, "ERROR", null, e.getMessage());
            }
        }
    }

    private void finish(BackgroundTask task, String status, QueryResult result, String message) {
        task.setStatus(status);
        task.setFinishedAt(java.time.LocalDateTime.now());
        task.setErrorMessage(message);
        taskWebSocketHandler.sendTaskUpdate(task.getId(), status,
                result != null ? result.getTotalRows() : 0, task.getElapsedMs(), message);
        log.info("后台任务终态: {} -> {}", task.getId(), status);
    }

    private void stashResult(String taskId, QueryResult result) {
        synchronized (resultOrder) {
            while (resultOrder.size() >= MAX_RESULTS) {
                String evicted = resultOrder.pollFirst();
                results.remove(evicted);
                BackgroundTask evictedTask = tasks.get(evicted);
                if (evictedTask != null) {
                    evictedTask.setResultAvailable(false);
                }
            }
            resultOrder.addLast(taskId);
            results.put(taskId, result);
        }
    }

    /** 任务注册表超限时淘汰最早的终态任务 */
    private void evictTasksIfNeeded() {
        if (tasks.size() < MAX_TASKS) {
            return;
        }
        tasks.values().stream()
                .filter(t -> !"RUNNING".equals(t.getStatus()))
                .min(Comparator.comparing(BackgroundTask::getSubmittedAt))
                .ifPresent(t -> remove(t.getId()));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
