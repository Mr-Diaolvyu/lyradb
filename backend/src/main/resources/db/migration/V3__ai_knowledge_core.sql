-- LyraDB 3.3：工作空间隔离的可信知识资产与 Verified Query。
-- 只有状态为 VERIFIED 的记录允许进入 AI 上下文。
CREATE TABLE IF NOT EXISTS ai_knowledge_asset (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(200) NOT NULL,
    definition CLOB NOT NULL,
    verified_sql CLOB,
    db_type VARCHAR(32),
    granted_source_name VARCHAR(100),
    default_database VARCHAR(200),
    keywords VARCHAR(1000),
    source_ref VARCHAR(1000),
    content_sha256 VARCHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    reviewed_by VARCHAR(36),
    review_comment VARCHAR(1000),
    asset_version INTEGER NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP,
    CONSTRAINT ck_ai_knowledge_status CHECK (
        status IN ('DRAFT', 'IN_REVIEW', 'VERIFIED', 'REJECTED', 'RETIRED')
    ),
    CONSTRAINT ck_ai_knowledge_type CHECK (
        asset_type IN ('BUSINESS_TERM', 'METRIC', 'TABLE_NOTE',
                       'COLUMN_NOTE', 'POLICY_RULE', 'VERIFIED_QUERY')
    )
);

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_workspace_status_updated
    ON ai_knowledge_asset(workspace_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_workspace_type
    ON ai_knowledge_asset(workspace_id, asset_type);
