-- =====================================================
-- V35: Align RAG lifecycle with Python-owned chunking
-- =====================================================

ALTER TABLE knowledge_doc
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
        COMMENT '文档状态: DRAFT/PENDING_REVIEW/APPROVED/INDEXING/PUBLISHED/DEPRECATED';

ALTER TABLE knowledge_chunk
    MODIFY COLUMN vector_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        COMMENT '向量状态: PENDING/INDEXED/SUPERSEDED/FAILED';

CREATE INDEX idx_chunk_doc_version
    ON knowledge_chunk (doc_id, version_no);
