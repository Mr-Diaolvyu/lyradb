-- LyraDB 3.4：受治理只读 Agent 运行索引。
-- 问题和 SQL 只保存 SHA-256；正文位于有期限的内存计划中。
CREATE TABLE IF NOT EXISTS ai_agent_run (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    granted_source_name VARCHAR(100) NOT NULL,
    agent_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    question_sha256 VARCHAR(64) NOT NULL,
    sql_sha256 VARCHAR(64) NOT NULL,
    plan_sha256 VARCHAR(64) NOT NULL,
    requested_rows INTEGER NOT NULL,
    estimated_cost_micros BIGINT NOT NULL,
    result_rows BIGINT,
    elapsed_ms BIGINT,
    expires_at TIMESTAMP NOT NULL,
    context_receipt_json CLOB,
    error_message VARCHAR(2000),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_ai_agent_status CHECK (
        status IN ('PLANNED', 'RUNNING', 'CANCEL_REQUESTED',
                   'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
    )
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_workspace_user_created
    ON ai_agent_run(workspace_id, user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_agent_workspace_status
    ON ai_agent_run(workspace_id, status);
