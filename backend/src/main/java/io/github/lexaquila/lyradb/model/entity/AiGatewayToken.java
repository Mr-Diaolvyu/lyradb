package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/** Agent Gateway 令牌索引；仅保存 SHA-256 与非敏感前缀。 */
@Entity
@Table(name = "ai_gateway_token", indexes = {
        @Index(name = "idx_ai_gateway_workspace_created",
                columnList = "workspace_id,created_at"),
        @Index(name = "idx_ai_gateway_token_hash",
                columnList = "token_sha256", unique = true)
})
@Data
public class AiGatewayToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "principal_user_id", nullable = false, length = 36)
    private String principalUserId;

    @Column(name = "grant_id", nullable = false, length = 36)
    private String grantId;

    @Column(name = "granted_source_name", nullable = false, length = 100)
    private String grantedSourceName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "token_sha256", nullable = false,
            unique = true, length = 64)
    private String tokenSha256;

    @Column(name = "token_prefix", nullable = false, length = 20)
    private String tokenPrefix;

    @Column(name = "scopes_csv", nullable = false, length = 500)
    private String scopesCsv;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
