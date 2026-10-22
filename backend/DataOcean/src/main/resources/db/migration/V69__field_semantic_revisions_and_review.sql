-- 独立平台字段语义事实、快照来源绑定、不可变修订、审核与候选。
-- V53 永久保留；当前最高已占用迁移为 V68。

ALTER TABLE metadata_quality_issue
    MODIFY COLUMN rule_id BIGINT DEFAULT NULL COMMENT 'SOURCE 规则 ID；SEMANTIC 问题可为空',
    ADD COLUMN issue_domain VARCHAR(20) NOT NULL DEFAULT 'SOURCE' COMMENT 'SOURCE/SEMANTIC，由服务端确定' AFTER rule_id,
    ADD COLUMN semantic_check_batch_id BIGINT DEFAULT NULL COMMENT '独立语义复查批次' AFTER check_batch_id,
    ADD COLUMN semantic_check_result_id BIGINT DEFAULT NULL COMMENT '语义复查证据结果' AFTER semantic_check_batch_id,
    ADD COLUMN semantic_rule_code VARCHAR(80) DEFAULT NULL COMMENT 'SEMANTIC 问题的服务端规则编码' AFTER semantic_check_result_id,
    ADD COLUMN semantic_key VARCHAR(256) DEFAULT NULL COMMENT '同一快照字段唯一语义问题键' AFTER semantic_rule_code,
    ADD INDEX idx_issue_domain_snapshot (issue_domain, snapshot_id, status),
    ADD INDEX idx_issue_semantic_batch (semantic_check_batch_id, status),
    ADD UNIQUE INDEX uk_semantic_issue_key (issue_domain, semantic_key);

ALTER TABLE query_task
    ADD COLUMN field_semantic_refs_json JSON DEFAULT NULL COMMENT '本任务固定的精确字段语义事实来源 ID，用于在途失效复核' AFTER rag_source_snapshot_id;

CREATE TABLE field_semantic_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    database_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    identity_hash CHAR(64) NOT NULL,
    current_binding_id BIGINT DEFAULT NULL,
    current_approved_revision_id BIGINT DEFAULT NULL,
    working_revision_id BIGINT DEFAULT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
    row_version INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_field_semantic_identity (datasource_id, identity_hash),
    INDEX idx_field_semantic_active (datasource_id, status, identity_hash),
    INDEX idx_field_semantic_current (current_binding_id, current_approved_revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段语义稳定主记录';

CREATE TABLE field_semantic_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    table_meta_id BIGINT NOT NULL,
    column_meta_id BIGINT NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    schema_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION' COMMENT 'VALID/PENDING_CONFIRMATION/NEEDS_REVIEW/UNAVAILABLE',
    status_reason VARCHAR(1000) DEFAULT NULL,
    confirmation_evidence VARCHAR(1000) DEFAULT NULL,
    confirmed_by BIGINT DEFAULT NULL,
    confirmed_at DATETIME DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_field_semantic_snapshot_binding (definition_id, snapshot_id),
    INDEX idx_field_semantic_binding_column (column_meta_id, snapshot_id, status),
    INDEX idx_field_semantic_binding_source (datasource_id, snapshot_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段语义与精确快照来源绑定';

CREATE TABLE field_semantic_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_id BIGINT NOT NULL,
    binding_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    based_on_revision_id BIGINT DEFAULT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/APPROVED/REJECTED/WITHDRAWN',
    business_name VARCHAR(200) NOT NULL,
    meaning TEXT NOT NULL,
    unit VARCHAR(200) DEFAULT NULL,
    currency VARCHAR(100) DEFAULT NULL,
    enum_semantics TEXT DEFAULT NULL,
    metric_definition TEXT DEFAULT NULL,
    applicability TEXT DEFAULT NULL,
    evidence TEXT NOT NULL,
    unresolved_items_json JSON DEFAULT NULL,
    glossary_refs_json JSON DEFAULT NULL,
    content_hash CHAR(64) NOT NULL,
    dependency_hash CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_field_semantic_revision_no (definition_id, revision_no),
    INDEX idx_field_semantic_revision_status (definition_id, status, revision_no),
    INDEX idx_field_semantic_revision_binding (binding_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不可变字段语义内容修订';

CREATE TABLE field_semantic_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    binding_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL COMMENT 'DRAFT_SAVED/SUBMIT/WITHDRAW/DRAFT_REPLACED/AI_ADOPTED/APPROVE/REJECT/REVOKE/BIND_CONFIRM',
    expected_row_version INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    dependency_hash CHAR(64) NOT NULL,
    reason VARCHAR(1000) DEFAULT NULL,
    operator_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_field_semantic_review_definition (definition_id, created_at DESC),
    INDEX idx_field_semantic_review_evidence (revision_id, binding_id, content_hash, dependency_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段语义审核及来源确认证据';

CREATE TABLE field_semantic_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_id BIGINT NOT NULL,
    binding_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    base_revision_id BIGINT DEFAULT NULL,
    base_row_version INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/READY/FAILED/ADOPTED/STALE',
    input_hash CHAR(64) NOT NULL,
    input_json JSON NOT NULL,
    business_name VARCHAR(200) DEFAULT NULL,
    meaning TEXT DEFAULT NULL,
    unit VARCHAR(200) DEFAULT NULL,
    currency VARCHAR(100) DEFAULT NULL,
    enum_semantics TEXT DEFAULT NULL,
    metric_definition TEXT DEFAULT NULL,
    applicability TEXT DEFAULT NULL,
    evidence TEXT DEFAULT NULL,
    unresolved_items_json JSON DEFAULT NULL,
    failure_reason VARCHAR(1000) DEFAULT NULL,
    adopted_revision_id BIGINT DEFAULT NULL,
    adopted_by BIGINT DEFAULT NULL,
    adopted_at DATETIME DEFAULT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_field_semantic_candidate_definition (definition_id, created_at DESC),
    INDEX idx_field_semantic_candidate_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 字段释义候选，不构成权威事实';

CREATE TABLE semantic_check_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/PARTIAL/FAILED',
    applicable_count INT NOT NULL DEFAULT 0,
    valid_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    stale_count INT NOT NULL DEFAULT 0,
    not_applicable_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(1000) DEFAULT NULL,
    rule_version INT NOT NULL DEFAULT 1,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME DEFAULT NULL,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_semantic_batch_snapshot (snapshot_id, started_at DESC),
    INDEX idx_semantic_batch_datasource (datasource_id, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='独立平台字段语义复查批次';

CREATE TABLE semantic_check_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    definition_id BIGINT DEFAULT NULL,
    binding_id BIGINT DEFAULT NULL,
    column_meta_id BIGINT NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    rule_code VARCHAR(80) NOT NULL COMMENT 'MISSING_FIELD_SEMANTIC/STALE_FIELD_SEMANTIC/NOT_APPLICABLE',
    outcome VARCHAR(24) NOT NULL COMMENT 'VALID/ISSUE/NOT_APPLICABLE/FAILED',
    rule_version INT NOT NULL,
    revision_id BIGINT DEFAULT NULL,
    review_id BIGINT DEFAULT NULL,
    evidence_hash CHAR(64) DEFAULT NULL,
    reason VARCHAR(1000) DEFAULT NULL,
    issue_id BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_semantic_result_column (batch_id, column_meta_id),
    INDEX idx_semantic_result_issue (issue_id),
    INDEX idx_semantic_result_definition (definition_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语义复查冻结依据与结果';
