package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageEntry;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageRisk;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次性导入预览缓存。内容只存在于有界短生命周期内存中，重启即失效。
 */
@Service
public class DataSourceImportPreviewStore {

    public static final int TTL_MINUTES = 10;
    private static final int MAX_GLOBAL = 100;
    private static final int MAX_PER_OWNER_WORKSPACE = 10;

    private final ConcurrentHashMap<String, PreviewSession> sessions =
            new ConcurrentHashMap<>();

    public synchronized PreviewSession create(
            String ownerId, String workspaceId,
            CredentialExportPolicy credentialPolicy,
            ConnectionPackageRisk risk,
            List<ConnectionPackageEntry> entries) {
        purgeExpired();
        if (sessions.size() >= MAX_GLOBAL) {
            throw new IllegalStateException("导入预览任务已达系统上限，请稍后重试");
        }
        long ownerCount = sessions.values().stream()
                .filter(value -> ownerId.equals(value.ownerId())
                        && workspaceId.equals(value.workspaceId()))
                .count();
        if (ownerCount >= MAX_PER_OWNER_WORKSPACE) {
            throw new IllegalStateException("当前用户的导入预览已达上限");
        }
        String token = UUID.randomUUID().toString();
        PreviewSession session = new PreviewSession(
                token, ownerId, workspaceId, credentialPolicy, risk,
                List.copyOf(entries),
                LocalDateTime.now().plusMinutes(TTL_MINUTES));
        sessions.put(token, session);
        return session;
    }

    /**
     * 仅所有者在原工作空间内可以原子消费；错误身份不会烧毁合法令牌。
     */
    public PreviewSession consume(
            String token, String ownerId, String workspaceId) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("previewToken 不能为空");
        }
        AtomicReference<PreviewSession> consumed = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        sessions.compute(token, (key, value) -> {
            if (value == null) {
                failure.set("导入预览不存在或已使用");
                return null;
            }
            if (!value.expiresAt().isAfter(LocalDateTime.now())) {
                failure.set("导入预览已过期");
                return null;
            }
            if (!ownerId.equals(value.ownerId())
                    || !workspaceId.equals(value.workspaceId())) {
                failure.set("导入预览不属于当前用户或工作空间");
                return value;
            }
            consumed.set(value);
            return null;
        });
        if (consumed.get() == null) {
            throw new RuntimeException(failure.get() == null
                    ? "导入预览不存在或已使用" : failure.get());
        }
        return consumed.get();
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        sessions.entrySet().removeIf(
                entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    @PreDestroy
    void clear() {
        sessions.clear();
    }

    public record PreviewSession(
            String token,
            String ownerId,
            String workspaceId,
            CredentialExportPolicy credentialPolicy,
            ConnectionPackageRisk risk,
            List<ConnectionPackageEntry> entries,
            LocalDateTime expiresAt) {
    }
}
