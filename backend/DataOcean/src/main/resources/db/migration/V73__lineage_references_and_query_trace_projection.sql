-- 新任务固定血缘来源身份；旧任务不回填为已验证。
ALTER TABLE query_task
    ADD COLUMN lineage_refs_json JSON NULL COMMENT '固定血缘来源、双端身份及依赖摘要',
    ADD COLUMN trace_projection_status VARCHAR(20) NULL COMMENT '受保护查询追溯投影：PENDING/COMPLETED',
    ADD INDEX idx_query_trace_projection (trace_projection_status, id);
ALTER TABLE knowledge_chunk ADD COLUMN source_fingerprint VARCHAR(64) NULL COMMENT '血缘来源不可变身份摘要';
ALTER TABLE rag_index_build_chunk ADD COLUMN source_fingerprint VARCHAR(64) NULL COMMENT '构建冻结的血缘来源摘要';
ALTER TABLE query_lineage_column MODIFY COLUMN alias_name TEXT NULL COMMENT '受保护结果的完整输出列名';
