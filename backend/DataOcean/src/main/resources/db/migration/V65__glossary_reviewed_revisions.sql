-- 术语定义与字段映射使用同一修订；不补造历史审核快照。
ALTER TABLE glossary_term
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '定义、映射及状态的并发版本',
    ADD COLUMN review_hash CHAR(64) NULL COMMENT '提交审核时完整定义与映射摘要';

CREATE TABLE glossary_term_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    term_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    reason VARCHAR(2000) NULL,
    actor_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_glossary_review_term (term_id, id)
) COMMENT='不可变的术语定义、映射与审核操作记录';

CREATE TABLE knowledge_semantic_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    base_version INT NOT NULL,
    candidate_text LONGTEXT NOT NULL,
    dependencies_json LONGTEXT NOT NULL,
    input_json LONGTEXT NOT NULL,
    confirmed_text LONGTEXT NULL,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_semantic_candidate_doc (doc_id, base_version)
) COMMENT='AI 释义候选与人工确认来源；不替代知识版本';
