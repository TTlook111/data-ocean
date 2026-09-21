-- IAM-SIMPLE-1 B3：查询任务执行证据与最终保护状态。
-- 不保存结构化记录条件参数原值；不修改 V54/V55，也不创建外键。
ALTER TABLE query_task ADD COLUMN iam_protocol_version VARCHAR(50) NULL COMMENT 'IAM-SIMPLE-1 协议版本' AFTER datasource_id;
ALTER TABLE query_task ADD COLUMN active_metadata_snapshot_id BIGINT NULL COMMENT 'S1 活动元数据快照' AFTER iam_protocol_version;
ALTER TABLE query_task ADD COLUMN permission_revision BIGINT NULL COMMENT 'S1 权限修订版本' AFTER active_metadata_snapshot_id;
ALTER TABLE query_task ADD COLUMN iam_execution_snapshot JSON NULL COMMENT '不含参数原值的 S1 执行快照' AFTER permission_revision;
ALTER TABLE query_task ADD COLUMN iam_resource_request JSON NULL COMMENT 'S1 原始资源引用安全摘要' AFTER iam_execution_snapshot;
ALTER TABLE query_task ADD COLUMN iam_source_trace JSON NULL COMMENT 'SQL 资源与字段来源安全摘要' AFTER iam_resource_request;
ALTER TABLE query_task ADD COLUMN iam_capabilities JSON NULL COMMENT 'S1 查询/SQL/导出能力摘要' AFTER iam_source_trace;
ALTER TABLE query_task ADD COLUMN iam_final_protection_status VARCHAR(64) NULL COMMENT 'Java 最终保护状态' AFTER iam_capabilities;
ALTER TABLE query_task ADD INDEX idx_query_task_iam_protocol (iam_protocol_version, user_id, permission_revision);
