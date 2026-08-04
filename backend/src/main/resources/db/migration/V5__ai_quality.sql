-- LyraDB 3.5：工作空间隔离的可信 AI 黄金集回归结果。
CREATE TABLE IF NOT EXISTS ai_evaluation_run (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    golden_set_version VARCHAR(32) NOT NULL,
    case_count INTEGER NOT NULL,
    passed_count INTEGER NOT NULL,
    average_score DOUBLE PRECISION NOT NULL,
    release_gate_passed BOOLEAN NOT NULL,
    report_json CLOB NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_eval_workspace_created
    ON ai_evaluation_run(workspace_id, created_at);
