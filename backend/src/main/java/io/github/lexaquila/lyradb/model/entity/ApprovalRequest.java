package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

/**
 * 审批申请单（状态机见 审计日志与审批工作流.md）
 */
@Entity
@Table(name = "ent_approval_request")
@Data
public class ApprovalRequest {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", length = 36)
    private String workspaceId;

    @Column(name = "applicant_id", nullable = false, length = 36)
    private String applicantId;

    @Column(name = "applicant_name", length = 100)
    private String applicantName;

    /** EXPORT / DML / MIGRATION / AI_DML */
    @Column(name = "operation_type", length = 16)
    private String operationType;

    @Column(name = "data_source_id", length = 36)
    private String dataSourceId;

    @Column(name = "granted_source_name", length = 100)
    private String grantedSourceName;

    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    @Column(length = 500)
    private String reason;

    /** DRAFT/PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED/EXECUTING/DONE/FAILED */
    @Column(length = 16)
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
