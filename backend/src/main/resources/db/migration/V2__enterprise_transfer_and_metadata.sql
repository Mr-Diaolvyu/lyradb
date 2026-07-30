-- v3.1.0 企业连接配置导出审批需要更长的操作类型。
ALTER TABLE ent_approval_request
    ALTER COLUMN operation_type VARCHAR(32);

-- 元数据快照审计只保存标识、规范化范围与内容摘要，不保存正文。
ALTER TABLE ent_audit_log
    ADD COLUMN IF NOT EXISTS details_json CLOB;

-- 批量数据源导出不绑定单个 grant_id，使用独立索引保证活动申请查询有界。
CREATE INDEX IF NOT EXISTS idx_approval_workspace_operation_payload
    ON ent_approval_request(
        applicant_id, workspace_id, operation_type,
        payload_hash, status, created_at);
