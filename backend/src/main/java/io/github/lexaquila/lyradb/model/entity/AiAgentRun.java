package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * Agent 运行审计与持久状态。问题只保存摘要；SQL 仅在计划有效期内以
 * AES-GCM 密文保存，终态后清除。
 */
@Entity
@Table(name = "ai_agent_run", indexes = {
        @Index(name = "idx_ai_agent_workspace_user_created",
                columnList = "workspace_id,user_id,created_at"),
        @Index(name = "idx_ai_agent_workspace_status",
                columnList = "workspace_id,status")
})
@Data
public class AiAgentRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "grant_id", nullable = false, length = 36)
    private String grantId;

    @Column(name = "granted_source_name", nullable = false, length = 100)
    private String grantedSourceName;

    @Column(name = "agent_type", nullable = false, length = 32)
    private String agentType = "GOVERNED_READ";

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "question_sha256", nullable = false, length = 64)
    private String questionSha256;

    @Column(name = "sql_sha256", nullable = false, length = 64)
    private String sqlSha256;

    @Column(name = "plan_sha256", nullable = false, length = 64)
    private String planSha256;

    @Lob
    @Column(name = "plan_payload_ciphertext")
    private String planPayloadCiphertext;

    @Column(name = "plan_consumed", nullable = false)
    private boolean planConsumed;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "execution_node_id", length = 128)
    private String executionNodeId;

    @Column(name = "requested_rows", nullable = false)
    private int requestedRows;

    @Column(name = "estimated_cost_micros", nullable = false)
    private long estimatedCostMicros;

    @Column(name = "result_rows")
    private Long resultRows;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Lob
    @Column(name = "context_receipt_json")
    private String contextReceiptJson;

    @Lob
    @Column(name = "tool_trace_json")
    private String toolTraceJson;

    @Column(name = "error_message", length = 2_000)
    private String errorMessage;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

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
