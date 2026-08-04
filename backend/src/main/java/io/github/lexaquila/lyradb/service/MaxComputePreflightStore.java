package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.model.entity.AiMaxComputePreflight;
import io.github.lexaquila.lyradb.repository.AiMaxComputePreflightRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** 数据库持久化、有期限且一次性的 MaxCompute 专项预检凭证。 */
@Component
public class MaxComputePreflightStore {

    private final AiMaxComputePreflightRepository repository;

    public MaxComputePreflightStore(
            AiMaxComputePreflightRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PreflightSession issue(
            String workspaceId,
            String userId,
            String grantId,
            String sqlSha256,
            long estimatedCostMicros,
            Instant expiresAt) {
        String nonce = UUID.randomUUID().toString();
        String token = AiDigest.sha256(String.join("\n",
                nonce, workspaceId, userId, grantId, sqlSha256,
                Long.toString(estimatedCostMicros), expiresAt.toString()));
        AiMaxComputePreflight entity = new AiMaxComputePreflight();
        entity.setTokenSha256(token);
        entity.setWorkspaceId(workspaceId);
        entity.setUserId(userId);
        entity.setGrantId(grantId);
        entity.setSqlSha256(sqlSha256);
        entity.setEstimatedCostMicros(estimatedCostMicros);
        entity.setExpiresAt(LocalDateTime.ofInstant(
                expiresAt, ZoneOffset.UTC));
        repository.saveAndFlush(entity);
        return toSession(entity);
    }

    @Transactional
    public void requireAndConsume(
            String tokenSha256,
            String workspaceId,
            String userId,
            String grantId,
            String sqlSha256,
            long estimatedCostMicros) {
        if (tokenSha256 == null || tokenSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "MaxCompute 计划必须携带专项预检摘要");
        }
        AiMaxComputePreflight entity = repository.findByTokenForUpdate(
                        tokenSha256.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException(
                        "MaxCompute 专项预检无效、已使用或已过期"));
        Instant expiresAt = entity.getExpiresAt().toInstant(ZoneOffset.UTC);
        if (!entity.getWorkspaceId().equals(workspaceId)
                || !entity.getUserId().equals(userId)
                || !entity.getGrantId().equals(grantId)
                || !entity.getSqlSha256().equals(sqlSha256)
                || entity.getEstimatedCostMicros() != estimatedCostMicros
                || !Instant.now().isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "MaxCompute 专项预检无效、已使用或已过期");
        }
        repository.delete(entity);
        repository.flush();
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    @Transactional
    void cleanupExpired() {
        repository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC));
    }

    private static PreflightSession toSession(
            AiMaxComputePreflight entity) {
        return new PreflightSession(
                entity.getTokenSha256(), entity.getWorkspaceId(),
                entity.getUserId(), entity.getGrantId(),
                entity.getSqlSha256(), entity.getEstimatedCostMicros(),
                entity.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    public record PreflightSession(
            String tokenSha256,
            String workspaceId,
            String userId,
            String grantId,
            String sqlSha256,
            long estimatedCostMicros,
            Instant expiresAt) {
    }
}
