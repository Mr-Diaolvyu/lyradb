package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.StatementRegistry;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 普通 SQL 的一次性执行句柄；取消只允许原用户在原工作空间操作。 */
@Service
public class EnterpriseQueryExecutionService {
    private final SecurityUtil security;
    private final GrantService grants;
    private final EnterpriseQueryService queries;
    private final AuditService audit;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public EnterpriseQueryExecutionService(SecurityUtil security, GrantService grants,
                                           EnterpriseQueryService queries, AuditService audit) {
        this.security = security;
        this.grants = grants;
        this.queries = queries;
        this.audit = audit;
    }

    public synchronized String prepare(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("逻辑数据源不能为空");
        String user = security.requireCurrentUser().getId();
        String workspace = security.requireCurrentWorkspace();
        grants.resolveForUser(user, workspace, source);
        expirePrepared();
        if (executions.size() >= 2000 || executions.values().stream()
                .filter(item -> item.user.equals(user)).count() >= 64) {
            throw new IllegalStateException("待执行查询过多，请等待或取消后重试");
        }
        String id = "ent-ui-" + UUID.randomUUID();
        StatementRegistry.prepare(id);
        executions.put(id, new Execution(user, workspace, source));
        return id;
    }

    public QueryResult execute(String id, String source, String sql, String database) throws Exception {
        Execution execution = requireOwned(id);
        if (!execution.source.equals(source)) throw new AccessDeniedException("执行句柄不属于当前数据源");
        if (!execution.started.compareAndSet(false, true)) {
            throw new IllegalStateException("执行句柄只能使用一次");
        }
        try {
            if (execution.cancelled.get()) throw new CancellationException("查询已取消");
            return queries.executeQuery(source, sql, database, id);
        } finally {
            executions.remove(id, execution);
            StatementRegistry.release(id);
        }
    }

    public boolean cancel(String id) {
        Execution execution = requireOwned(id);
        execution.cancelled.set(true);
        boolean accepted = StatementRegistry.requestCancellation(id);
        audit.recordCurrent(execution.workspace, "QUERY_CANCEL", null, execution.source, accepted, null);
        return accepted;
    }

    private Execution requireOwned(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("执行标识不能为空");
        Execution execution = executions.get(id);
        String user = security.requireCurrentUser().getId();
        String workspace = security.requireCurrentWorkspace();
        if (execution == null || !execution.user.equals(user) || !execution.workspace.equals(workspace)) {
            throw new AccessDeniedException("执行不存在、已结束或无权访问");
        }
        return execution;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expirePrepared() {
        long cutoff = System.currentTimeMillis() - 600_000;
        executions.forEach((id, item) -> {
            if (item.createdAt < cutoff && item.started.compareAndSet(false, true) && executions.remove(id, item)) {
                StatementRegistry.release(id);
            }
        });
    }

    private static final class Execution {
        final String user;
        final String workspace;
        final String source;
        final long createdAt = System.currentTimeMillis();
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        Execution(String user, String workspace, String source) {
            this.user = user;
            this.workspace = workspace;
            this.source = source;
        }
    }
}
