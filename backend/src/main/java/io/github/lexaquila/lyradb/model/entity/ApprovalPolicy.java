package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

/**
 * 审批策略（工作空间级，覆盖全局默认）
 */
@Entity
@Table(name = "ent_approval_policy")
@Data
public class ApprovalPolicy {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", length = 36)
    private String workspaceId;

    /** 用户决策：任何导出都要审批 */
    @Column(name = "always_approve_export")
    private boolean alwaysApproveExport = true;

    @Column(name = "dml_row_threshold")
    private int dmlRowThreshold = 1000;

    @Column(name = "always_approve_migration")
    private boolean alwaysApproveMigration = true;

    @Column(name = "always_approve_ai_dml")
    private boolean alwaysApproveAiDml = true;

    /** 敏感表，逗号分隔 */
    @Column(name = "sensitive_tables", length = 1000)
    private String sensitiveTables;

    /** 默认审批角色 */
    @Column(name = "approver_role", length = 32)
    private String approverRole = "STEWARD";

    /** 是否需要双人审批（高风险场景：迁移/敏感表） */
    @Column(name = "require_two_approvers")
    private boolean requireTwoApprovers = false;
}
