-- Phase 1 #6: 治理-置信度联动 — MetadataQualityIssue 新增 columnMetaId
-- 目的：issue 创建时预存，handleIssue() 审核时 O(1) 读取直接联动置信度

ALTER TABLE metadata_quality_issue ADD COLUMN column_meta_id BIGINT DEFAULT NULL;

CREATE INDEX idx_issue_column_meta ON metadata_quality_issue(column_meta_id);
