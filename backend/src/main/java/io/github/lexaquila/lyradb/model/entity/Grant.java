
package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 数据源授权（逻辑数据源，授予用户/角色）
 *
 * <p>用户只可见 {@link #grantedSourceName} 与可访问范围；不可见真实 {@link #dataSourceId} 的连接信息。</p>
 */
@Entity
@Table(name = "ent_grant", uniqueConstraints = @UniqueConstraint(
        name = "uk_grant_user_workspace_name",
        columnNames = {"user_id", "workspace_id", "granted_source_name"}))
@Data
public class Grant {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** 真实数据源引用（用户不可见的连接信息本体） */
    @Column(name = "data_source_id", nullable = false, length = 36)
    private String dataSourceId;

    /** 逻辑显示名 */
    @Column(name = "granted_source_name", nullable = false, length = 100)
    private String grantedSourceName;

    /** 授予对象：userId（可扩展为角色/组） */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** 允许的 schema，逗号分隔，空=全部 */
    @Column(name = "allowed_schemas", length = 500)
    private String allowedSchemas;

    /** 允许的表，逗号分隔，可含末尾通配；空=不允许访问任何表 */
    @Column(name = "allowed_tables", length = 1000)
    private String allowedTables;

    /** 黑名单表 */
    @Column(name = "blocked_tables", length = 1000)
    private String blockedTables;

    /** READ_ONLY / DML_ALLOWED */
    @Column(name = "sql_capability", nullable = false, length = 16)
    private String sqlCapability = "READ_ONLY";

    @Column(name = "max_rows_per_query", nullable = false)
    private int maxRowsPerQuery = 10000;

    @Column(name = "export_approved_only", nullable = false)
    private boolean exportApprovedOnly = true;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
