-- V50: 为 RAG chunk 增加文档内顺序、语义分组和来源 metadata

ALTER TABLE knowledge_chunk
    ADD COLUMN chunk_index INT NULL COMMENT '文档版本内的切片顺序，从 0 开始' AFTER version_no,
    ADD COLUMN chunk_group_id VARCHAR(100) NULL COMMENT '同一语义小节或可一起扩展的切片分组' AFTER chunk_index,
    ADD COLUMN related_tables TEXT NULL COMMENT '关联表名 JSON 数组，兼容多表 Join Path' AFTER related_table,
    ADD COLUMN related_columns TEXT NULL COMMENT '关联字段名 JSON 数组' AFTER related_tables,
    ADD COLUMN entity_ids TEXT NULL COMMENT '关联实体 ID JSON 数组' AFTER related_columns,
    ADD COLUMN trust_score INT NULL COMMENT '来源可信度' AFTER entity_ids,
    ADD COLUMN content_hash CHAR(64) NULL COMMENT '切片内容 SHA-256' AFTER trust_score;

CREATE INDEX idx_chunk_doc_version_index
    ON knowledge_chunk (doc_id, version_no, chunk_index);

CREATE INDEX idx_chunk_doc_version_group
    ON knowledge_chunk (doc_id, version_no, chunk_group_id);
