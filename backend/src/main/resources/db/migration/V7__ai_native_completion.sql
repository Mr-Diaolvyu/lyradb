-- LyraDB 3.8：AI 原生能力完成契约所需的持久运行态、检索与评测字段。

ALTER TABLE ai_knowledge_asset
    ADD COLUMN IF NOT EXISTS ingestion_source VARCHAR(64);
ALTER TABLE ai_knowledge_asset
    ADD COLUMN IF NOT EXISTS lineage_json CLOB;
ALTER TABLE ai_knowledge_asset
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(200);
ALTER TABLE ai_knowledge_asset
    ADD COLUMN IF NOT EXISTS embedding_json CLOB;

ALTER TABLE ai_evaluation_run
    ADD COLUMN IF NOT EXISTS evaluation_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE ai_evaluation_run
    ADD COLUMN IF NOT EXISTS provider_key VARCHAR(32);
ALTER TABLE ai_evaluation_run
    ADD COLUMN IF NOT EXISTS model_name VARCHAR(200);
ALTER TABLE ai_evaluation_run
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_evaluation_run
    ADD COLUMN IF NOT EXISTS total_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ai_agent_run
    ADD COLUMN IF NOT EXISTS plan_payload_ciphertext CLOB;
ALTER TABLE ai_agent_run
    ADD COLUMN IF NOT EXISTS plan_consumed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_agent_run
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_agent_run
    ADD COLUMN IF NOT EXISTS execution_node_id VARCHAR(128);
ALTER TABLE ai_agent_run
    ADD COLUMN IF NOT EXISTS tool_trace_json CLOB;

ALTER TABLE ai_provider_config
    ADD COLUMN IF NOT EXISTS deployment_mode VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

CREATE TABLE IF NOT EXISTS ai_maxcompute_preflight (
    token_sha256 VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    sql_sha256 VARCHAR(64) NOT NULL,
    estimated_cost_micros BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_mc_preflight_expires
    ON ai_maxcompute_preflight(expires_at);
