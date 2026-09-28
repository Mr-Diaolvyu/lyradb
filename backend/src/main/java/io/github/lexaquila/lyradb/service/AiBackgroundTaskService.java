package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import jakarta.annotation.PreDestroy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/** 有界、按用户和空间隔离的 AI 后台任务。仅保存于当前服务进程，不跨重启恢复。 */
@Service
public class AiBackgroundTaskService {
    private final SecurityUtil security;
    private final GrantService grants;
    private final ApprovalSecurityContextService fingerprints;
    private final EnterpriseAiService ai;
    private final EnterpriseTableSearchService search;
    private final EnterpriseMetadataSnapshotService metadata;
    private final AuditService audit;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread thread = new Thread(r, "ai-background-task");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AiBackgroundTaskService(SecurityUtil security, GrantService grants,
            ApprovalSecurityContextService fingerprints, EnterpriseAiService ai,
            EnterpriseTableSearchService search, EnterpriseMetadataSnapshotService metadata,
            AuditService audit) {
        this.security = security;
        this.grants = grants;
        this.fingerprints = fingerprints;
        this.ai = ai;
        this.search = search;
        this.metadata = metadata;
        this.audit = audit;
    }

    public synchronized View submit(String workspaceId, Request request) {
        validate(request);
        security.requireWorkspaceAccess(workspaceId);
        User user = security.requireCurrentUser();
        cleanup();
        // 客户端请求标识避免网络响应丢失后的重复提交。
        String key = workspaceId + ":" + user.getId() + ":" + request.requestId();
        Task existing = tasks.get(key);
        if (existing != null) {
            if (!existing.request.equals(request)) throw new IllegalArgumentException("请求标识已用于其他任务");
            authorize(existing);
            return view(existing);
        }
        if (tasks.size() >= 256 || tasks.values().stream().filter(t -> t.owner.equals(user.getId())
                && active(t)).count() >= 4) {
            throw new IllegalArgumentException("后台任务较多，请等待已有任务完成");
        }
        Grant grant = grants.resolveForUser(user.getId(), workspaceId, request.grantedSourceName());
        Task task = new Task(request, workspaceId, user.getId(), grant.getId(), fingerprints.fingerprint(grant));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        tasks.put(key, task);
        try {
            executor.submit(() -> execute(task, authentication));
        } catch (RejectedExecutionException exception) {
            tasks.remove(key);
            throw new IllegalArgumentException("后台任务队列已满，请稍后重试");
        }
        return view(task);
    }

    public synchronized List<View> list(String workspaceId) {
        security.requireWorkspaceAccess(workspaceId);
        String owner = security.requireCurrentUser().getId();
        cleanup();
        List<View> result = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (!task.owner.equals(owner) || !task.workspace.equals(workspaceId)) continue;
            try {
                authorize(task);
                result.add(view(task));
            } catch (AccessDeniedException | IllegalArgumentException exception) {
                // 已撤销授权的任务连同历史结果一并隐藏。
            }
        }
        result.sort(Comparator.comparingLong(View::createdAt).reversed());
        return result;
    }

    private void execute(Task task, Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            Object result = security.inBackgroundWorkspace(task.workspace, () -> {
                authorize(task);
                task.state = "RUNNING";
                Request request = task.request;
                return switch (request.kind()) {
                    case "METADATA" -> {
                        var captured = metadata.capture(task.workspace, security.requireCurrentUser(),
                                new EnterpriseMetadataSnapshotService.CaptureRequest(request.grantedSourceName(),
                                        request.database(), request.schemas(), request.tables()));
                        audit.recordCurrentMetadata(task.workspace, "AI_METADATA_SNAPSHOT_CREATE",
                                captured.dataSourceId(), captured.grantedSourceName(), captured.id(),
                                new MetadataSnapshotSessionStore.MapScope(captured.database(), captured.schemas(), captured.tables()),
                                captured.contentSha256());
                        yield captured;
                    }
                    case "FIND_TABLE" -> search.search(task.workspace, request.grantedSourceName(), request.message(), 12);
                    default -> ai.chat(task.workspace, request.grantedSourceName(), request.message(),
                            request.history(), request.attachMetadata(), request.metadataSnapshotId());
                };
            });
            authorize(task);
            if (result instanceof Map<?, ?> map && map.get("error") instanceof String error && !error.isBlank()) {
                task.error = error;
                task.result = result;
                task.state = "FAILED";
            } else {
                task.result = result;
                task.state = "SUCCEEDED";
            }
        } catch (Exception exception) {
            // 不将底层异常或连接凭据通过任务列表返回给客户端。
            String message = Objects.toString(exception.getMessage(), "");
            task.error = exception instanceof IllegalArgumentException && (
                    message.startsWith("未采集到任何表。") || message.startsWith("元数据快照表数量超过")
                    || message.startsWith("元数据快照字段数量超过"))
                    ? message : exception instanceof AccessDeniedException
                    ? "任务授权已失效，请检查数据源授权后重新发起"
                    : "任务执行失败，请检查连接或 AI 服务配置后重试";
            task.state = "FAILED";
        } finally {
            task.finishedAt = System.currentTimeMillis();
            SecurityContextHolder.clearContext();
        }
    }

    private void authorize(Task task) {
        security.requireWorkspaceAccess(task.workspace);
        Grant current = grants.getByIdForUser(task.grantId, task.owner, task.workspace);
        if (!task.fingerprint.equals(fingerprints.fingerprint(current))) {
            throw new AccessDeniedException("任务创建后授权或连接配置已变化");
        }
    }

    private static boolean active(Task task) {
        return "QUEUED".equals(task.state) || "RUNNING".equals(task.state);
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        tasks.values().removeIf(t -> t.finishedAt > 0 && t.finishedAt < cutoff);
    }

    private static View view(Task task) {
        String state = task.state;
        return new View(task.request.requestId(), task.request.kind(), task.request.grantedSourceName(),
                task.request.message(), state, task.createdAt, task.finishedAt,
                task.request.attachMetadata(), task.request.metadataSnapshotId(), task.result, task.error);
    }

    private static void validate(Request request) {
        if (request == null || request.requestId() == null || !request.requestId().matches("[a-zA-Z0-9-]{16,64}")
                || !Set.of("CHAT", "FIND_TABLE", "METADATA").contains(Objects.toString(request.kind(), ""))
                || request.grantedSourceName() == null || request.grantedSourceName().isBlank()) {
            throw new IllegalArgumentException("任务类型、数据源或请求标识无效");
        }
        if (!"METADATA".equals(request.kind()) && (request.message() == null || request.message().isBlank()
                || request.message().length() > 16000)) throw new IllegalArgumentException("问题不能为空且不能超过 16000 字符");
        if (request.history().size() > 40 || request.history().stream().anyMatch(m -> m == null
                || !Set.of("user", "assistant").contains(m.getOrDefault("role", ""))
                || m.getOrDefault("content", "").length() > 32000)) {
            throw new IllegalArgumentException("对话历史过长或格式错误，请开始新的对话");
        }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public record Request(String requestId, String kind, String grantedSourceName, String message,
                          List<Map<String, String>> history, boolean attachMetadata, String metadataSnapshotId,
                          String database, List<String> schemas, List<String> tables) {
        public Request {
            history = history == null ? List.of() : history.stream().map(Map::copyOf).toList();
            schemas = schemas == null ? List.of() : List.copyOf(schemas);
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }
    public record View(String id, String kind, String grantedSourceName, String message, String state,
                       long createdAt, long finishedAt, boolean metadataAttached, String metadataSnapshotId,
                       Object result, String error) {}

    private static class Task {
        final Request request;
        final String workspace, owner, grantId, fingerprint;
        final long createdAt = System.currentTimeMillis();
        volatile long finishedAt;
        volatile String state = "QUEUED", error;
        volatile Object result;
        Task(Request request, String workspace, String owner, String grantId, String fingerprint) {
            this.request = request;
            this.workspace = workspace;
            this.owner = owner;
            this.grantId = grantId;
            this.fingerprint = fingerprint;
        }
    }
}
