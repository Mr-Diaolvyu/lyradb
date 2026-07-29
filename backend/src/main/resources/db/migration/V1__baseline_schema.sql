


-- LyraDB 3.0.0 基线结构。
--
-- 设计目标：
-- 1. 空库可一次创建完整结构；
-- 2. 历史库通过 Flyway baselineVersion=0 后执行本脚本时保留已有数据；
-- 3. 所有安全边界新增字段均使用幂等 DDL，允许升级过程重复校验。

CREATE TABLE IF NOT EXISTS connection_config (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    connection_params CLOB,
    group_name VARCHAR(255),
    color VARCHAR(255),
    description VARCHAR,
    tags VARCHAR(255),
    favorite BOOLEAN,
    sort_order INTEGER,
    auto_connect BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS query_history (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36),
    db_type VARCHAR(32),
    sql_text CLOB NOT NULL,
    title VARCHAR(200),
    is_favorite BOOLEAN,
    tags VARCHAR(500),
    executed_at TIMESTAMP,
    duration_ms BIGINT,
    row_count BIGINT,
    is_success BOOLEAN,
    error_message VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS sys_workspace (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    owner_id VARCHAR(36),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    email VARCHAR(200),
    display_name VARCHAR(100),
    enabled BOOLEAN NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(32),
    CONSTRAINT fk_user_role_user
        FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS sys_user_workspace (
    user_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_user_workspace_user
        FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_workspace_workspace
        FOREIGN KEY (workspace_id) REFERENCES sys_workspace(id)
);

CREATE TABLE IF NOT EXISTS sys_workspace_membership (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    roles_csv VARCHAR(256) NOT NULL DEFAULT 'ANALYST',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_workspace_membership_user_workspace
        UNIQUE (user_id, workspace_id)
);

CREATE TABLE IF NOT EXISTS ent_data_source (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    db_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    connection_params_json CLOB NOT NULL,
    description VARCHAR(500),
    created_by VARCHAR(36),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ent_grant (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    data_source_id VARCHAR(36) NOT NULL,
    granted_source_name VARCHAR(100) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    allowed_schemas VARCHAR(500),
    allowed_tables VARCHAR(1000),
    blocked_tables VARCHAR(1000),
    sql_capability VARCHAR(16) NOT NULL DEFAULT 'READ_ONLY',
    max_rows_per_query INTEGER NOT NULL DEFAULT 1,
    export_approved_only BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP,
    created_at TIMESTAMP,
    CONSTRAINT uk_grant_user_workspace_name
        UNIQUE (user_id, workspace_id, granted_source_name)
);

CREATE TABLE IF NOT EXISTS ai_provider_config (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    provider_key VARCHAR(16),
    display_name VARCHAR(100),
    base_url VARCHAR(255),
    api_key VARCHAR(500),
    model VARCHAR(100),
    temperature DOUBLE PRECISION,
    max_tokens INTEGER,
    is_enabled BOOLEAN,
    is_default BOOLEAN,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ent_approval_policy (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    always_approve_export BOOLEAN,
    dml_row_threshold INTEGER,
    always_approve_migration BOOLEAN,
    always_approve_ai_dml BOOLEAN,
    sensitive_tables VARCHAR(1000),
    approver_role VARCHAR(32) NOT NULL DEFAULT 'STEWARD',
    require_two_approvers BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_approval_policy_workspace UNIQUE (workspace_id)
);

CREATE TABLE IF NOT EXISTS ent_approval_request (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    applicant_id VARCHAR(36) NOT NULL,
    applicant_name VARCHAR(100),
    operation_type VARCHAR(16),
    data_source_id VARCHAR(36),
    grant_id VARCHAR(36),
    granted_source_name VARCHAR(100),
    security_context_hash VARCHAR(64),
    payload_json CLOB,
    payload_hash VARCHAR(64),
    reason VARCHAR(500),
    status VARCHAR(24),
    approver_id VARCHAR(36),
    approver_count INTEGER,
    approver_ids VARCHAR(200),
    approver_comment VARCHAR(1000),
    risk_score INTEGER,
    expires_at TIMESTAMP,
    executed_at TIMESTAMP,
    execution_result CLOB,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ent_masking_rule (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    data_source_id VARCHAR(36),
    table_pattern VARCHAR(200),
    column_pattern VARCHAR(500),
    mask_type VARCHAR(16),
    remark VARCHAR(200),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ent_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36),
    user_id VARCHAR(36),
    username VARCHAR(100),
    role VARCHAR(32),
    data_source_id VARCHAR(36),
    granted_source_name VARCHAR(100),
    db_type VARCHAR(32),
    operation_type VARCHAR(32),
    action VARCHAR(64),
    sql_text CLOB,
    sql_hash VARCHAR(64),
    affected_rows BIGINT,
    result_rows BIGINT,
    elapsed_ms BIGINT,
    is_success BOOLEAN,
    error_message VARCHAR(2000),
    ip VARCHAR(64),
    user_agent VARCHAR(255),
    session_id VARCHAR(64),
    approval_request_id VARCHAR(36),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS report_schedule (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    owner_username VARCHAR(100),
    workspace_id VARCHAR(36),
    connection_id VARCHAR(36) NOT NULL,
    connection_name VARCHAR(100),
    sql VARCHAR(4000) NOT NULL,
    default_database VARCHAR(100),
    schedule_type VARCHAR(16) NOT NULL,
    run_minute INTEGER,
    run_hour INTEGER,
    weekday INTEGER,
    webhook_url VARCHAR(500) NOT NULL,
    enabled BOOLEAN,
    created_at TIMESTAMP,
    last_run_at TIMESTAMP,
    last_status VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS report_run (
    id VARCHAR(36) PRIMARY KEY,
    schedule_id VARCHAR(36) NOT NULL,
    run_at TIMESTAMP,
    success BOOLEAN,
    row_count BIGINT,
    elapsed_ms BIGINT,
    push_status VARCHAR(16),
    error_message VARCHAR(1000)
);

-- 历史库兼容：先以可回填形式添加字段，再收紧可安全收紧的约束。
-- 历史 connection_params 可能由 Hibernate 建成 VARCHAR，统一转换为实体要求的 CLOB。
ALTER TABLE connection_config
    ALTER COLUMN connection_params CLOB;
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS credential_version BIGINT DEFAULT 0;
UPDATE sys_user SET credential_version = 0 WHERE credential_version IS NULL;
ALTER TABLE sys_user ALTER COLUMN credential_version SET DEFAULT 0;
ALTER TABLE sys_user ALTER COLUMN credential_version SET NOT NULL;

-- 运行时以 sys_workspace_membership 为唯一租户成员关系来源；升级时完整迁移旧关联及工作空间角色。
INSERT INTO sys_workspace_membership(
    id, user_id, workspace_id, roles_csv, created_at, updated_at)
SELECT CAST(RANDOM_UUID() AS VARCHAR(36)), legacy.user_id, legacy.workspace_id,
       COALESCE((
           SELECT LISTAGG(DISTINCT role_row.role, ',')
                  WITHIN GROUP (ORDER BY role_row.role)
             FROM sys_user_role role_row
            WHERE role_row.user_id = legacy.user_id
              AND role_row.role IN ('ANALYST', 'DS_ADMIN', 'STEWARD', 'AUDITOR')
       ), 'ANALYST'), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM sys_user_workspace legacy
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_workspace_membership membership
        WHERE membership.user_id = legacy.user_id
          AND membership.workspace_id = legacy.workspace_id
 );

ALTER TABLE ent_approval_request
    ALTER COLUMN status VARCHAR(24);
ALTER TABLE ent_approval_request
    ADD COLUMN IF NOT EXISTS grant_id VARCHAR(36);
ALTER TABLE ent_approval_request
    ADD COLUMN IF NOT EXISTS security_context_hash VARCHAR(64);
ALTER TABLE ent_approval_request
    ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64);
ALTER TABLE ent_approval_request
    ADD COLUMN IF NOT EXISTS approver_count INTEGER DEFAULT 0;
ALTER TABLE ent_approval_request
    ADD COLUMN IF NOT EXISTS approver_ids VARCHAR(200) DEFAULT '';
UPDATE ent_approval_request SET approver_count = 0 WHERE approver_count IS NULL;
UPDATE ent_approval_request SET approver_ids = '' WHERE approver_ids IS NULL;
-- 历史审批按原授权主键尽力回填；缺少上下文指纹的可执行审批必须失效重申。
UPDATE ent_approval_request approval
SET grant_id = (
    SELECT grant_row.id
      FROM ent_grant grant_row
     WHERE grant_row.user_id = approval.applicant_id
       AND grant_row.workspace_id = approval.workspace_id
       AND grant_row.data_source_id = approval.data_source_id
       AND grant_row.granted_source_name = approval.granted_source_name
)
WHERE approval.grant_id IS NULL;
UPDATE ent_approval_request
SET status = 'INVALIDATED'
WHERE status IN ('PENDING', 'APPROVED')
  AND (grant_id IS NULL OR security_context_hash IS NULL OR payload_hash IS NULL);

-- 核心企业资源先按强关联精确推导；只有全库恰好一个工作空间时才允许兜底。
-- 多工作空间中的孤儿记录保持 NULL，让后续 NOT NULL 约束明确阻断升级，
-- 管理员必须先按业务归属人工补齐 workspace_id，禁止随机归入任一租户。
UPDATE ent_data_source source
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE source.workspace_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;

UPDATE ent_grant grant_row
SET workspace_id = (
    SELECT source.workspace_id
      FROM ent_data_source source
     WHERE source.id = grant_row.data_source_id
)
WHERE grant_row.workspace_id IS NULL;
UPDATE ent_grant grant_row
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE grant_row.workspace_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;

UPDATE ent_approval_request approval
SET workspace_id = (
    SELECT source.workspace_id
      FROM ent_data_source source
     WHERE source.id = approval.data_source_id
)
WHERE approval.workspace_id IS NULL
  AND approval.data_source_id IS NOT NULL;
UPDATE ent_approval_request approval
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE approval.workspace_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;

UPDATE ai_provider_config provider
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE provider.workspace_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;

UPDATE ent_approval_policy policy
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE policy.workspace_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;

ALTER TABLE ent_data_source ALTER COLUMN workspace_id SET NOT NULL;
-- 当前模型仅支持用户授权；历史 NULL user_id 属于无法判定归属的孤儿授权，
-- 必须在升级前人工绑定用户，否则此约束会阻断迁移。
ALTER TABLE ent_grant ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE ent_grant ALTER COLUMN workspace_id SET NOT NULL;
UPDATE ent_grant SET sql_capability = 'READ_ONLY'
 WHERE sql_capability IS NULL OR TRIM(sql_capability) = '';
UPDATE ent_grant SET max_rows_per_query = 1
 WHERE max_rows_per_query IS NULL OR max_rows_per_query <= 0;
UPDATE ent_grant SET export_approved_only = TRUE
 WHERE export_approved_only IS NULL;
ALTER TABLE ent_grant ALTER COLUMN sql_capability SET DEFAULT 'READ_ONLY';
ALTER TABLE ent_grant ALTER COLUMN sql_capability SET NOT NULL;
ALTER TABLE ent_grant ALTER COLUMN max_rows_per_query SET DEFAULT 1;
ALTER TABLE ent_grant ALTER COLUMN max_rows_per_query SET NOT NULL;
ALTER TABLE ent_grant ALTER COLUMN export_approved_only SET DEFAULT TRUE;
ALTER TABLE ent_grant ALTER COLUMN export_approved_only SET NOT NULL;
ALTER TABLE ai_provider_config ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE ent_approval_policy ALTER COLUMN workspace_id SET NOT NULL;
UPDATE ent_approval_policy SET approver_role = 'STEWARD'
 WHERE approver_role IS NULL OR TRIM(approver_role) = '';
UPDATE ent_approval_policy SET require_two_approvers = FALSE
 WHERE require_two_approvers IS NULL;
ALTER TABLE ent_approval_policy ALTER COLUMN approver_role SET DEFAULT 'STEWARD';
ALTER TABLE ent_approval_policy ALTER COLUMN approver_role SET NOT NULL;
ALTER TABLE ent_approval_policy ALTER COLUMN require_two_approvers SET DEFAULT FALSE;
ALTER TABLE ent_approval_policy ALTER COLUMN require_two_approvers SET NOT NULL;
ALTER TABLE ent_approval_request ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE ent_grant
    ADD CONSTRAINT IF NOT EXISTS uk_grant_user_workspace_name
        UNIQUE (user_id, workspace_id, granted_source_name);
ALTER TABLE ent_approval_policy
    ADD CONSTRAINT IF NOT EXISTS uk_approval_policy_workspace
        UNIQUE (workspace_id);

ALTER TABLE ent_masking_rule
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(36);
ALTER TABLE ent_masking_rule
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE;
UPDATE ent_masking_rule SET enabled = TRUE WHERE enabled IS NULL;
ALTER TABLE ent_masking_rule ALTER COLUMN enabled SET DEFAULT TRUE;
ALTER TABLE ent_masking_rule ALTER COLUMN enabled SET NOT NULL;
UPDATE ent_masking_rule rule
SET workspace_id = (
    SELECT source.workspace_id
      FROM ent_data_source source
     WHERE source.id = rule.data_source_id
)
WHERE rule.workspace_id IS NULL
  AND rule.data_source_id IS NOT NULL;
UPDATE ent_masking_rule rule
SET workspace_id = (SELECT MIN(workspace.id) FROM sys_workspace workspace)
WHERE rule.workspace_id IS NULL
  AND rule.data_source_id IS NULL
  AND (SELECT COUNT(*) FROM sys_workspace) = 1;
ALTER TABLE ent_masking_rule
    ADD CONSTRAINT IF NOT EXISTS ck_masking_workspace_manual_mapping_required
        CHECK (workspace_id IS NOT NULL);
ALTER TABLE ent_masking_rule
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE ent_audit_log
    ADD COLUMN IF NOT EXISTS action VARCHAR(64);
ALTER TABLE ent_audit_log
    ALTER COLUMN operation_type VARCHAR(32);

ALTER TABLE report_schedule
    ADD COLUMN IF NOT EXISTS owner_username VARCHAR(100);
ALTER TABLE report_schedule
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(36);
UPDATE report_schedule SET owner_username = 'personal'
 WHERE owner_username IS NULL OR TRIM(owner_username) = '';
UPDATE report_schedule SET workspace_id = 'personal'
 WHERE workspace_id IS NULL OR TRIM(workspace_id) = '';
ALTER TABLE report_schedule ALTER COLUMN owner_username SET DEFAULT 'personal';
ALTER TABLE report_schedule ALTER COLUMN owner_username SET NOT NULL;
ALTER TABLE report_schedule ALTER COLUMN workspace_id SET DEFAULT 'personal';
ALTER TABLE report_schedule ALTER COLUMN workspace_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_membership_user
    ON sys_workspace_membership(user_id);
CREATE INDEX IF NOT EXISTS idx_membership_workspace
    ON sys_workspace_membership(workspace_id);
CREATE INDEX IF NOT EXISTS idx_approval_workspace_status
    ON ent_approval_request(workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_approval_applicant_created
    ON ent_approval_request(applicant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_approval_grant_status
    ON ent_approval_request(grant_id, status);
CREATE INDEX IF NOT EXISTS idx_approval_payload_lookup
    ON ent_approval_request(
        applicant_id, workspace_id, grant_id,
        operation_type, payload_hash, status, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_workspace_created
    ON ent_audit_log(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_user_created
    ON ent_audit_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_approval_request
    ON ent_audit_log(approval_request_id);
CREATE INDEX IF NOT EXISTS idx_masking_workspace_source_enabled
    ON ent_masking_rule(workspace_id, data_source_id, enabled);
