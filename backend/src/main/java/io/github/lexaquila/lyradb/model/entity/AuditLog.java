package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 操作审计日志（append-only）
 */
@Entity
@Table(name = "ent_audit_log", indexes = {
        @Index(name = "idx_audit_workspace_created",
                columnList = "workspace_id,created_at"),
        @Index(name = "idx_audit_user_created",
                columnList = "user_id,created_at"),
        @Index(name = "idx_audit_approval",
                columnList = "approval_request_id")
})
@Data
public class AuditLog {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", length = 36)
    private String workspaceId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(length = 100)
    private String username;

    @Column(length = 32)
    private String role;

    @Column(name = "data_source_id", length = 36)
    private String dataSourceId;

    @Column(name = "granted_source_name", length = 100)
    private String grantedSourceName;

    @Column(name = "db_type", length = 32)
    private String dbType;

    /** QUERY / EXPORT / UPDATE / DDL / MIGRATION / AI_QUERY / LOGIN / ADMIN */
    @Column(name = "operation_type", length = 32)
    private String operationType;

    /** 细粒度动作，例如 USER_CREATE、APPROVAL_APPROVE。 */
    @Column(length = 64)
    private String action;

    @Lob
    @Column(name = "sql_text")
    private String sqlText;

    @Column(name = "sql_hash", length = 64)
    private String sqlHash;

    @Column(name = "affected_rows")
    private Long affectedRows;

    @Column(name = "result_rows")
    private Long resultRows;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "is_success")
    private Boolean success;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "approval_request_id", length = 36)
    private String approvalRequestId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
