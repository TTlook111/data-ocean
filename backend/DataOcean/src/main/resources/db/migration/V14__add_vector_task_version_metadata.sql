-- =====================================================
-- V14: Add knowledge version metadata to vector tasks
-- =====================================================

ALTER TABLE knowledge_doc_version
    ADD COLUMN dependency_snapshot LONGTEXT NULL COMMENT '该版本生成时的依赖快照 JSON' AFTER metadata_snapshot_id;

ALTER TABLE vector_index_task
    ADD COLUMN metadata_snapshot_id BIGINT NULL COMMENT '关联元数据快照 ID',
    ADD COLUMN knowledge_version_no INT NULL COMMENT '待向量化的 skills.md 版本号',
    ADD COLUMN previous_version_no INT NULL COMMENT '成功写入新版本后待清理的旧 skills.md 版本号';

CREATE INDEX idx_vector_task_doc_version
    ON vector_index_task (target_id, knowledge_version_no);

CREATE INDEX idx_vector_task_snapshot
    ON vector_index_task (metadata_snapshot_id);
