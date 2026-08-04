-- LyraDB 3.7：独立身份、单 Grant、细粒度范围的 Agent Gateway 令牌。
-- 明文令牌永不落库。
CREATE TABLE IF NOT EXISTS ai_gateway_token (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    principal_user_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    granted_source_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    token_sha256 VARCHAR(64) NOT NULL,
    token_prefix VARCHAR(20) NOT NULL,
    scopes_csv VARCHAR(500) NOT NULL,
    credential_version BIGINT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_gateway_token_hash UNIQUE (token_sha256)
);

CREATE INDEX IF NOT EXISTS idx_ai_gateway_workspace_created
    ON ai_gateway_token(workspace_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_gateway_token_hash
    ON ai_gateway_token(token_sha256);
