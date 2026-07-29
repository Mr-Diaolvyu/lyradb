package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 用户在单个工作空间内的角色绑定。
 */
@Entity
@Table(name = "sys_workspace_membership",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workspace_membership_user_workspace",
                columnNames = {"user_id", "workspace_id"}),
        indexes = {
                @Index(name = "idx_membership_user", columnList = "user_id"),
                @Index(name = "idx_membership_workspace", columnList = "workspace_id")
        })
@Data
public class WorkspaceMembership {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** 逗号分隔的工作空间级角色，不允许 PLATFORM_ADMIN。 */
    @Column(name = "roles_csv", nullable = false, length = 256)
    private String rolesCsv = "ANALYST";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
