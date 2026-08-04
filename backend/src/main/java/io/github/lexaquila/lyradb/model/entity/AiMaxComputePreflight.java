package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** 可跨进程校验、一次性消费的 MaxCompute 专项预检凭证。 */
@Entity
@Table(name = "ai_maxcompute_preflight", indexes = {
        @Index(name = "idx_ai_mc_preflight_expires",
                columnList = "expires_at")
})
@Data
public class AiMaxComputePreflight {

    /** 调用方持有的随机摘要；只保存摘要本身，不保存 SQL。 */
    @Id
    @Column(name = "token_sha256", length = 64)
    private String tokenSha256;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "grant_id", nullable = false, length = 36)
    private String grantId;

    @Column(name = "sql_sha256", nullable = false, length = 64)
    private String sqlSha256;

    @Column(name = "estimated_cost_micros", nullable = false)
    private long estimatedCostMicros;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
