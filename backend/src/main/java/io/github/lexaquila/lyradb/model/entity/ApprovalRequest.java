


package io.github.lexaquila.lyradb.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 审批申请单（状态机见 审计日志与审批工作流.md）
 */
@Entity
@Table(name = "ent_approval_request", indexes = {
        @Index(name = "idx_approval_workspace_status", columnList = "workspace_id,status"),
        @Index(name = "idx_approval_applicant_created", columnList = "applicant_id,created_at")
})
@Data
public class ApprovalRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "applicant_id", nullable = false, length = 36)
    private String applicantId;

    @Column(name = "applicant_name", length = 100)
    private String applicantName;

    /** EXPORT / DANGEROUS_SQL / DATASOURCE_EXPORT */
    @Column(name = "operation_type", length = 32)
    private String operationType;

    @Column(name = "data_source_id", length = 36)
    private String dataSourceId;

    /** 审批创建时绑定的授权主键，防止删除后用同名授权替换。 */
    @Column(name = "grant_id", length = 36)
    private String grantId;

    @Column(name = "granted_source_name", length = 100)
    private String grantedSourceName;

    /** 数据源、授权范围和脱敏规则的 SHA-256 安全上下文指纹。 */
    @JsonIgnore
    @Column(name = "security_context_hash", length = 64)
    private String securityContextHash;

    /** AES-GCM 加密后的规范化载荷，不直接序列化给客户端。 */
    @JsonIgnore
    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    /** 规范化载荷的 keyed HMAC blind index，用于有界精确检索与去重。 */
    @JsonIgnore
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(length = 500)
    private String reason;

    /** PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED/INVALIDATED/EXECUTING/EXECUTION_UNKNOWN/DONE/FAILED */
    @Column(length = 24)
    private String status = "DRAFT";

    @Column(name = "approver_id", length = 36)
    private String approverId;

    /** 双人审批：已批准人数 */
    @Column(name = "approver_count")
    private int approverCount = 0;

    /** 双人审批：已批准人ID（逗号分隔，防同人重复） */
    @Column(name = "approver_ids", length = 200)
    private String approverIds = "";

    @Column(name = "approver_comment", length = 1000)
    private String approverComment;

    @Column(name = "risk_score")
    private int riskScore;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Lob
    @Column(name = "execution_result")
    private String executionResult;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
