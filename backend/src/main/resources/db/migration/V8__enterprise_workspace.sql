ALTER TABLE ent_data_source ADD COLUMN IF NOT EXISTS last_test_status VARCHAR(24);
ALTER TABLE ent_data_source ADD COLUMN IF NOT EXISTS last_tested_at TIMESTAMP;
ALTER TABLE ent_data_source ADD COLUMN IF NOT EXISTS last_test_elapsed_ms BIGINT;
ALTER TABLE ent_data_source ADD COLUMN IF NOT EXISTS last_test_error_code VARCHAR(80);
ALTER TABLE ent_data_source ADD COLUMN IF NOT EXISTS last_test_config_hash VARCHAR(64);

CREATE TABLE IF NOT EXISTS ent_saved_sql (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    granted_source_name VARCHAR(100) NOT NULL,
    title VARCHAR(100) NOT NULL,
    encrypted_sql CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_saved_sql_owner
    ON ent_saved_sql(workspace_id, user_id, updated_at);

CREATE TABLE IF NOT EXISTS ent_query_history (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    granted_source_name VARCHAR(100) NOT NULL,
    encrypted_sql CLOB NOT NULL,
    succeeded BOOLEAN NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_query_history_owner
    ON ent_query_history(workspace_id, user_id, created_at);
