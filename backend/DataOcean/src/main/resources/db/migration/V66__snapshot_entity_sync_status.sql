-- 记录发布快照到 metadata_entity/metadata_relationship 的目录同步结果。
-- 目录页据此区分“已完整同步”“同步失败”“历史版本未记录”，不能把失败状态显示成正常目录。
ALTER TABLE metadata_snapshot
    ADD COLUMN entity_sync_status VARCHAR(16) NULL COMMENT '目录同步状态：SYNCING / SYNCED / FAILED' AFTER published_at,
    ADD COLUMN entity_sync_error VARCHAR(1000) NULL COMMENT '目录同步失败原因（截断后保存）' AFTER entity_sync_status,
    ADD COLUMN entity_synced_at DATETIME NULL COMMENT '目录同步完成时间' AFTER entity_sync_error;
