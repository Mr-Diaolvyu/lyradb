package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 用户与工作空间绑定的短生命周期元数据快照缓存。
 */
@Service
public class MetadataSnapshotSessionStore {

    public static final int TTL_MINUTES = 30;
    private static final int MAX_GLOBAL = 100;
    private static final int MAX_PER_OWNER_WORKSPACE = 10;
    private static final long MAX_GLOBAL_CONTENT_BYTES =
            64L * 1024L * 1024L;

    private final ConcurrentHashMap<String, SnapshotSession> sessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> contentBytesById =
            new ConcurrentHashMap<>();
    private final MetadataSnapshotRenderer renderer =
            new MetadataSnapshotRenderer();
    private final long maxGlobalContentBytes;
    private final Supplier<LocalDateTime> currentTime;
    private long totalContentBytes;

    public MetadataSnapshotSessionStore() {
        this(MAX_GLOBAL_CONTENT_BYTES, LocalDateTime::now);
    }

    MetadataSnapshotSessionStore(long maxGlobalContentBytes) {
        this(maxGlobalContentBytes, LocalDateTime::now);
    }

    MetadataSnapshotSessionStore(
            long maxGlobalContentBytes, Supplier<LocalDateTime> currentTime) {
        if (maxGlobalContentBytes <= 0) {
            throw new IllegalArgumentException(
                    "元数据缓存字节预算必须大于 0");
        }
        this.maxGlobalContentBytes = maxGlobalContentBytes;
        if (currentTime == null) {
            throw new IllegalArgumentException("元数据缓存时钟不能为空");
        }
        this.currentTime = currentTime;
    }

    public synchronized SnapshotSession create(
            String ownerId, String workspaceId,
            String grantId, String dataSourceId,
            String grantedSourceName, String securityContextHash,
            MapScope scope, MetadataSnapshot snapshot,
            int tableCount, int columnCount, long estimatedTokens) {
        purgeExpired();
        long contentBytes = renderer.toJsonUtf8(snapshot).length;
        if (contentBytes > maxGlobalContentBytes - totalContentBytes) {
            throw new IllegalStateException(
                    "元数据快照缓存已达 64 MiB 总字节上限");
        }
        if (sessions.size() >= MAX_GLOBAL) {
            throw new IllegalStateException("元数据快照任务已达系统上限");
        }
        long ownerCount = sessions.values().stream()
                .filter(value -> ownerId.equals(value.ownerId())
                        && workspaceId.equals(value.workspaceId()))
                .count();
        if (ownerCount >= MAX_PER_OWNER_WORKSPACE) {
            throw new IllegalStateException("当前用户的元数据快照已达上限");
        }
        String id = UUID.randomUUID().toString();
        SnapshotSession session = new SnapshotSession(
                id, ownerId, workspaceId, grantId, dataSourceId,
                grantedSourceName, securityContextHash, scope, snapshot,
                tableCount, columnCount, estimatedTokens,
                currentTime.get().plusMinutes(TTL_MINUTES), false);
        sessions.put(id, session);
        contentBytesById.put(id, contentBytes);
        totalContentBytes += contentBytes;
        return session;
    }

    public synchronized SnapshotSession require(
            String id, String ownerId, String workspaceId) {
        SnapshotSession session = sessions.get(id);
        if (session == null) {
            throw new RuntimeException("元数据快照不存在或已失效");
        }
        if (!session.expiresAt().isAfter(currentTime.get())) {
            removeSession(id, session);
            throw new RuntimeException("元数据快照已过期");
        }
        if (!ownerId.equals(session.ownerId())
                || !workspaceId.equals(session.workspaceId())) {
            throw new RuntimeException(
                    "元数据快照不属于当前用户或工作空间");
        }
        return session;
    }

    public synchronized SnapshotSession consumeForAi(
            String id, String ownerId, String workspaceId) {
        SnapshotSession value = sessions.get(id);
        if (value == null) {
            throw new RuntimeException("元数据快照不存在或已失效");
        }
        if (!value.expiresAt().isAfter(currentTime.get())) {
            removeSession(id, value);
            throw new RuntimeException("元数据快照已过期");
        }
        if (!ownerId.equals(value.ownerId())
                || !workspaceId.equals(value.workspaceId())) {
            throw new RuntimeException(
                    "元数据快照不属于当前用户或工作空间");
        }
        if (value.aiConsumed()) {
            throw new RuntimeException(
                    "元数据快照已附加过，不得重复使用");
        }
        SnapshotSession consumed = new SnapshotSession(
                value.id(), value.ownerId(), value.workspaceId(),
                value.grantId(), value.dataSourceId(),
                value.grantedSourceName(), value.securityContextHash(),
                value.scope(), value.snapshot(), value.tableCount(),
                value.columnCount(), value.estimatedTokens(),
                value.expiresAt(), true);
        sessions.put(id, consumed);
        return consumed;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public synchronized void purgeExpired() {
        LocalDateTime now = currentTime.get();
        for (String id : List.copyOf(sessions.keySet())) {
            SnapshotSession session = sessions.get(id);
            if (session != null
                    && !session.expiresAt().isAfter(now)) {
                removeSession(id, session);
            }
        }
    }

    public synchronized void discard(
            String id, String ownerId, String workspaceId) {
        SnapshotSession session = require(id, ownerId, workspaceId);
        removeSession(id, session);
    }

    private void removeSession(
            String id, SnapshotSession expected) {
        if (!sessions.remove(id, expected)) {
            return;
        }
        Long released = contentBytesById.remove(id);
        if (released == null || released < 0
                || released > totalContentBytes) {
            sessions.put(id, expected);
            if (released != null) {
                contentBytesById.put(id, released);
            }
            throw new IllegalStateException(
                    "元数据快照缓存字节计量不一致");
        }
        totalContentBytes -= released;
    }

    @PreDestroy
    synchronized void clear() {
        sessions.clear();
        contentBytesById.clear();
        totalContentBytes = 0;
    }

    public record MapScope(
            String database, List<String> schemas,
            List<String> tables) {
        public MapScope {
            schemas = schemas == null ? List.of() : List.copyOf(schemas);
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record SnapshotSession(
            String id,
            String ownerId,
            String workspaceId,
            String grantId,
            String dataSourceId,
            String grantedSourceName,
            String securityContextHash,
            MapScope scope,
            MetadataSnapshot snapshot,
            int tableCount,
            int columnCount,
            long estimatedTokens,
            LocalDateTime expiresAt,
            boolean aiConsumed) {
    }
}
