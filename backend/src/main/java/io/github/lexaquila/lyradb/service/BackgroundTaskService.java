package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.BackgroundTask;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
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
 * 后台查询任务服务。
 *
 * <p>任务、结果、取消和通知均绑定提交者与工作空间，调用方无法枚举、
 * 获取或取消其他用户的任务。</p>
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
    private final Deque<String> resultOrder = new ArrayDeque<>();

    public BackgroundTaskService(QueryService queryService, TaskWebSocketHandler taskWebSocketHandler) {
        this.queryService = queryService;
        this.taskWebSocketHandler = taskWebSocketHandler;
    }

    public synchronized BackgroundTask submit(String ownerUsername, String workspaceId, String connectionId,
            String connectionName, String sql, String defaultDatabase, boolean force) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new AccessDeniedException("必须登录后才能提交后台任务");
        }
        evictTasksIfNeeded();

        BackgroundTask task = new BackgroundTask();
        task.setId(UUID.randomUUID().toString());
        task.setOwnerUsername(ownerUsername);
        task.setWorkspaceId(workspaceId);
        task.setConnectionId(connectionId);
        task.setConnectionName(connectionName);
        task.setSql(sql);
        tasks.put(task.getId(), task);

        Future<?> future = executor.submit(() -> run(task, defaultDatabase, force));
        task.setFuture(future);
        log.info("后台任务已提交: {} (owner={}, connectionId={})",
                task.getId(), ownerUsername, connectionId);
        return task;
    }

    public List<BackgroundTask> list(String ownerUsername, String workspaceId) {
        List<BackgroundTask> list = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            if (belongsTo(task, ownerUsername, workspaceId)) {
                list.add(task);
            }
        }
        list.sort(Comparator.comparing(BackgroundTask::getSubmittedAt).reversed());
        return list;
    }

    public QueryResult getResult(String taskId, String ownerUsername, String workspaceId) {
        BackgroundTask task = requireOwned(taskId, ownerUsername, workspaceId);
        QueryResult result = results.get(task.getId());
        if (result == null) {
            throw new IllegalStateException("结果已失效（暂存区已淘汰），请重新执行");
        }
        return result;
    }

    public boolean cancel(String taskId, String ownerUsername, String workspaceId) {
        BackgroundTask task = requireOwned(taskId, ownerUsername, workspaceId);
        synchronized (task) {
            if (!"RUNNING".equals(task.getStatus())) {
                return false;
            }
            // 先落 CANCELLED，避免中断线程抢先把任务改成 ERROR。
            task.setStatus("CANCELLED");
            task.setFinishedAt(java.time.LocalDateTime.now());
            task.setErrorMessage("已取消");
        }
        if (task.getFuture() != null) {
            task.getFuture().cancel(true);
        }
        queryService.cancelExecution(task.getId());
        taskWebSocketHandler.sendTaskUpdate(task.getOwnerUsername(), task.getWorkspaceId(),
                task.getId(), "CANCELLED", 0, task.getElapsedMs(), "已取消");
        log.info("后台任务终态: {} -> CANCELLED", task.getId());
        return true;
    }

    public void remove(String taskId, String ownerUsername, String workspaceId) {
        BackgroundTask task = requireOwned(taskId, ownerUsername, workspaceId);
        if ("RUNNING".equals(task.getStatus())) {
            throw new IllegalStateException("任务运行中，请先取消");
        }
        removeInternal(taskId);
    }

    private void run(BackgroundTask task, String defaultDatabase, boolean force) {
        long start = System.currentTimeMillis();
        try {
            QueryResult result = queryService.executeQuery(task.getConnectionId(), task.getSql(),
                    defaultDatabase, force, task.getId());
            task.setElapsedMs(System.currentTimeMillis() - start);
            if ("CANCELLED".equals(task.getStatus())) {
                return;
            }
            if (result.isReviewBlocked()) {
                finish(task, "ERROR", null, "命中 SQL 审核拦截，请在编辑器中确认后重新提交");
                return;
            }
            stashResult(task.getId(), result);
            task.setTotalRows(result.getTotalRows());
            task.setResultAvailable(true);
            finish(task, "DONE", result, null);
        } catch (Exception e) {
            task.setElapsedMs(System.currentTimeMillis() - start);
            if (!"CANCELLED".equals(task.getStatus())) {
                finish(task, "ERROR", null, "后台查询执行失败");
                log.warn("后台任务执行失败: {} - {}", task.getId(), e.getClass().getSimpleName());
            }
        }
    }

    private void finish(BackgroundTask task, String status, QueryResult result, String message) {
        synchronized (task) {
            if (isTerminal(task.getStatus()) && !"RUNNING".equals(status)) {
                return;
            }
            task.setStatus(status);
            task.setFinishedAt(java.time.LocalDateTime.now());
            task.setErrorMessage(message);
        }
        taskWebSocketHandler.sendTaskUpdate(task.getOwnerUsername(), task.getWorkspaceId(),
                task.getId(), status, result != null ? result.getTotalRows() : 0,
                task.getElapsedMs(), message);
        log.info("后台任务终态: {} -> {}", task.getId(), status);
    }

    private BackgroundTask requireOwned(String taskId, String ownerUsername, String workspaceId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!belongsTo(task, ownerUsername, workspaceId)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        return task;
    }

    private boolean belongsTo(BackgroundTask task, String ownerUsername, String workspaceId) {
        if (ownerUsername == null || !ownerUsername.equals(task.getOwnerUsername())) {
            return false;
        }
        String taskWorkspace = task.getWorkspaceId();
        return taskWorkspace != null && !taskWorkspace.isBlank()
                && taskWorkspace.equals(workspaceId);
    }

    private boolean isTerminal(String status) {
        return "DONE".equals(status) || "ERROR".equals(status) || "CANCELLED".equals(status);
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

    private void evictTasksIfNeeded() {
        if (tasks.size() < MAX_TASKS) {
            return;
        }
        tasks.values().stream()
                .filter(t -> !"RUNNING".equals(t.getStatus()))
                .min(Comparator.comparing(BackgroundTask::getSubmittedAt))
                .ifPresent(t -> removeInternal(t.getId()));
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("后台任务已达到上限，请等待运行中任务完成");
        }
    }

    private void removeInternal(String taskId) {
        tasks.remove(taskId);
        synchronized (resultOrder) {
            results.remove(taskId);
            resultOrder.remove(taskId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
