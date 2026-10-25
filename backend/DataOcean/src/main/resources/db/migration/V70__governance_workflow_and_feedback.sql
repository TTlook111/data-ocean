-- 平台治理任务、业务知识修订、查询期档案和版本化反馈，不触碰业务源数据库。
CREATE TABLE governance_audit_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, object_type VARCHAR(32) NOT NULL, object_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL, payload_json JSON NOT NULL, operator_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_governance_audit_object(object_type,object_id,id)
);
ALTER TABLE query_task
 ADD COLUMN governance_profiles_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '新任务固定启用查询期字段档案',
 ADD COLUMN business_knowledge_refs_json JSON NULL COMMENT '当前任务固定的业务知识精确修订';
CREATE TABLE governance_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 datasource_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, column_meta_id BIGINT NULL,
 business_revision_id BIGINT NULL, target_key VARCHAR(200) NOT NULL,
 table_name VARCHAR(255) NOT NULL, column_name VARCHAR(255) NOT NULL,
 origin VARCHAR(24) NOT NULL, base_revision_id BIGINT NULL, expected_version INT NULL,
 status VARCHAR(32) NOT NULL, reason TEXT NULL, candidate_id BIGINT NULL,
 created_by BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 0, locked_until DATETIME NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_governance_task_target(snapshot_id, column_meta_id),
 UNIQUE KEY uk_governance_task_key(snapshot_id, target_key),
 KEY idx_governance_task_worker(status, locked_until), KEY idx_governance_task_source(datasource_id, snapshot_id)
);
CREATE TABLE governance_field_usage (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL,
 datasource_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, column_meta_id BIGINT NOT NULL,
 definition_id BIGINT NULL, revision_id BIGINT NULL, field_name VARCHAR(512) NOT NULL,
 profile_json JSON NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_governance_usage(task_id, column_meta_id)
);
CREATE TABLE governance_feedback (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,
 datasource_id BIGINT NOT NULL, feedback_type VARCHAR(16) NOT NULL, reason_code VARCHAR(32) NOT NULL,
 comment TEXT NULL, column_meta_id BIGINT NULL, definition_id BIGINT NULL, revision_id BIGINT NULL,
 status VARCHAR(32) NOT NULL, followup_status VARCHAR(24) NOT NULL DEFAULT 'NONE', reviewed_by BIGINT NULL, review_note TEXT NULL, row_version INT NOT NULL DEFAULT 1,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_governance_feedback(task_id, user_id), KEY idx_governance_feedback_review(datasource_id, status)
);
CREATE TABLE governance_confidence_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, feedback_id BIGINT NOT NULL, feedback_version INT NOT NULL,
 definition_id BIGINT NOT NULL, revision_id BIGINT NOT NULL, direction VARCHAR(16) NOT NULL,
 active INT NOT NULL DEFAULT 1, evidence_key CHAR(64) NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_governance_confidence_event(feedback_id, feedback_version),
 KEY idx_governance_confidence_revision(definition_id, revision_id, active)
);
CREATE TABLE business_knowledge_revision (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, datasource_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL,
 knowledge_key VARCHAR(160) NOT NULL, kind VARCHAR(24) NOT NULL, title VARCHAR(255) NOT NULL,
 body_json JSON NOT NULL, dependencies_json JSON NOT NULL, source_hash CHAR(64) NOT NULL, source_json JSON NOT NULL, content_hash CHAR(64) NOT NULL,
 evidence TEXT NULL, unresolved_items_json JSON NOT NULL, origin VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL, created_by BIGINT NOT NULL, reviewed_by BIGINT NULL, review_note TEXT NULL,
 verified_hash CHAR(64) NULL, row_version INT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_business_knowledge_revision(datasource_id, snapshot_id, knowledge_key, id),
 KEY idx_business_knowledge_status(datasource_id, snapshot_id, status)
);
