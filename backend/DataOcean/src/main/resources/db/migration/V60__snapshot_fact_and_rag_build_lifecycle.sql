-- Snapshot-bound fact provenance and explicit datasource RAG builds.
-- V53 is intentionally unused. V59 is the highest applied migration on this branch.

ALTER TABLE table_relation
    ADD COLUMN review_status VARCHAR(24) NULL COMMENT '关系审核状态：CONFIRMED/PENDING/REJECTED',
    ADD COLUMN reviewed_by BIGINT NULL COMMENT '人工确认关系的用户 ID',
    ADD COLUMN reviewed_at DATETIME NULL COMMENT '关系确认时间';

UPDATE table_relation
SET review_status = CASE
        WHEN relation_type = 'FK' THEN 'CONFIRMED'
        ELSE 'PENDING'
    END,
    reviewed_at = CASE WHEN relation_type = 'FK' THEN created_at ELSE NULL END
WHERE review_status IS NULL;

ALTER TABLE knowledge_chunk
    ADD COLUMN governance_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '快照事实治理状态',
    ADD COLUMN resource_dependencies TEXT NULL COMMENT '完整资源依赖 JSON；不是从正文猜测',
    ADD COLUMN fact_source_ids TEXT NULL COMMENT '来源事实 ID JSON',
    ADD COLUMN fact_type VARCHAR(32) NULL COMMENT '事实类型：TABLE/COLUMN/JOIN/LINEAGE/METRIC',
    ADD COLUMN fact_review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '单条事实审核状态';

-- Existing chunks were generated without complete fact dependencies and are not eligible for
-- a newly confirmed build. Their document/version history remains available for review/rebuild.

UPDATE metadata_relationship AS lineage
JOIN metadata_entity AS source_entity ON source_entity.id = lineage.source_id
JOIN metadata_entity AS target_entity ON target_entity.id = lineage.target_id
SET lineage.relation_metadata = JSON_SET(
    COALESCE(lineage.relation_metadata, JSON_OBJECT()),
    '$.source_fqn', source_entity.fqn,
    '$.target_fqn', target_entity.fqn,
    '$.source_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED),
    '$.target_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(target_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED),
    '$.confirmation_status', CASE
        WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(lineage.relation_metadata, '$.lineage_type')), '')) IN ('MANUAL', 'ETL')
            THEN 'CONFIRMED'
        ELSE 'PENDING'
    END,
    '$.binding_status', CASE
        WHEN JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id'))
             = JSON_UNQUOTE(JSON_EXTRACT(target_entity.entity_metadata, '$.snapshot_id'))
            THEN 'BOUND'
        ELSE 'UNBOUND'
    END,
    '$.bound_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED)
)
WHERE lineage.relation_type = 'LINEAGE';

UPDATE metadata_relationship AS derived
JOIN metadata_entity AS source_entity ON source_entity.id = derived.source_id
JOIN metadata_entity AS target_entity ON target_entity.id = derived.target_id
LEFT JOIN metadata_relationship AS parent_lineage
    ON parent_lineage.relation_type = 'LINEAGE'
    AND parent_lineage.source_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(derived.relation_metadata, '$.parent_lineage_table_source_id')) AS UNSIGNED)
    AND parent_lineage.target_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(derived.relation_metadata, '$.parent_lineage_table_target_id')) AS UNSIGNED)
SET derived.relation_metadata = JSON_SET(
    COALESCE(derived.relation_metadata, JSON_OBJECT()),
    '$.source_fqn', source_entity.fqn,
    '$.target_fqn', target_entity.fqn,
    '$.source_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED),
    '$.target_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(target_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED),
    '$.confirmation_status', CASE
        WHEN parent_lineage.id IS NOT NULL
             AND JSON_UNQUOTE(JSON_EXTRACT(parent_lineage.relation_metadata, '$.confirmation_status')) = 'CONFIRMED'
            THEN 'CONFIRMED'
        ELSE 'PENDING'
    END,
    '$.binding_status', CASE
        WHEN JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id'))
             = JSON_UNQUOTE(JSON_EXTRACT(target_entity.entity_metadata, '$.snapshot_id'))
            THEN 'BOUND'
        ELSE 'UNBOUND'
    END,
    '$.bound_snapshot_id', CAST(JSON_UNQUOTE(JSON_EXTRACT(source_entity.entity_metadata, '$.snapshot_id')) AS UNSIGNED)
)
WHERE derived.relation_type = 'DERIVED_FROM';

CREATE TABLE rag_index_build (
    build_id                    VARCHAR(64)  NOT NULL COMMENT 'UUID, immutable RAG build identity',
    datasource_id               BIGINT       NOT NULL,
    source_snapshot_id          BIGINT       NOT NULL COMMENT '元数据来源快照；不是独立数据源版本',
    collection_name             VARCHAR(128) NOT NULL COMMENT '每个 build 独立 Milvus collection',
    embedding_provider_id       VARCHAR(100) NOT NULL,
    embedding_model             VARCHAR(200) NOT NULL,
    embedding_base_url          VARCHAR(500) NULL,
    embedding_dimension         INT          NOT NULL,
    embedding_index_version     VARCHAR(100) NULL,
    embedding_fingerprint       CHAR(64)     NOT NULL COMMENT '不含密钥的向量空间配置指纹',
    build_generation            BIGINT       NOT NULL,
    manifest_json               JSON         NOT NULL COMMENT '确认时冻结的文档版本和 chunk 集合',
    status                      VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    expected_chunk_count        INT          NOT NULL DEFAULT 0,
    actual_vector_count         INT          NOT NULL DEFAULT 0,
    confirmation_user_id        BIGINT       NOT NULL COMMENT '明确确认本次构建的用户',
    confirmed_at                DATETIME     NOT NULL,
    started_at                  DATETIME     NULL,
    verified_at                 DATETIME     NULL,
    activated_at                DATETIME     NULL,
    superseded_at               DATETIME     NULL,
    cleanup_after               DATETIME     NULL,
    cleanup_verified_at         DATETIME     NULL,
    error_message               VARCHAR(1000) NULL,
    created_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (build_id),
    UNIQUE KEY uk_rag_build_generation (datasource_id, build_generation),
    UNIQUE KEY uk_rag_build_collection (collection_name),
    KEY idx_rag_build_status (status, created_at),
    KEY idx_rag_build_source (datasource_id, source_snapshot_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工确认的数据源 RAG 构建记录';

CREATE TABLE rag_index_state (
    datasource_id       BIGINT      NOT NULL PRIMARY KEY,
    active_build_id     VARCHAR(64) NULL COMMENT '唯一对查询可见的生效构建',
    latest_build_id     VARCHAR(64) NULL COMMENT '最新确认代际，阻止迟到构建覆盖',
    next_generation     BIGINT      NOT NULL DEFAULT 1,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_rag_state_active_build (active_build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源当前生效 RAG 构建指针';

CREATE TABLE rag_index_build_chunk (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    build_id                VARCHAR(64)  NOT NULL,
    chunk_id                BIGINT       NOT NULL,
    datasource_id           BIGINT       NOT NULL,
    source_snapshot_id      BIGINT       NOT NULL,
    doc_id                  BIGINT       NOT NULL,
    version_no              INT          NOT NULL,
    resource_dependencies   TEXT         NOT NULL COMMENT '该事实完整的表/字段依赖 JSON',
    fact_source_ids         TEXT         NOT NULL COMMENT '来源 fact ID JSON',
    fact_type               VARCHAR(32)  NOT NULL,
    fact_review_status      VARCHAR(24)  NOT NULL,
    governance_status       VARCHAR(32)  NOT NULL,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_build_chunk (build_id, chunk_id),
    KEY idx_rag_build_chunk_fallback (build_id, datasource_id, source_snapshot_id, doc_id, version_no),
    KEY idx_rag_build_chunk_id (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建到受审核事实 chunk 的冻结清单';

ALTER TABLE query_task
    ADD COLUMN rag_build_id VARCHAR(64) NULL COMMENT '本次查询固定使用的生效 RAG buildId',
    ADD COLUMN rag_source_snapshot_id BIGINT NULL COMMENT '本次 RAG 的来源元数据快照';

CREATE INDEX idx_query_task_rag_build_status
    ON query_task (datasource_id, rag_build_id, status);

ALTER TABLE vector_index_task
    ADD COLUMN build_id VARCHAR(64) NULL COMMENT '新 RAG 生命周期使用的 buildId',
    ADD COLUMN target_collection VARCHAR(128) NULL COMMENT '构建隔离的 Milvus collection';
