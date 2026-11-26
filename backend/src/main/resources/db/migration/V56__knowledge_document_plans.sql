-- 按当前数据源保存用户发起的规划及确切治理快照，模型建议不写入文档。
CREATE TABLE kn_document_plan (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    requested_by BIGINT UNSIGNED NOT NULL,
    authentication_version BIGINT UNSIGNED NOT NULL,
    model_revision_id BIGINT UNSIGNED NOT NULL,
    snapshot JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'QUEUED',
    result JSON NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    started_at DATETIME(3) NULL,
    ended_at DATETIME(3) NULL,
    KEY ix_kn_plan_dispatch (state, source_id, id),
    CONSTRAINT fk_kn_plan_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT fk_kn_plan_model FOREIGN KEY (model_revision_id) REFERENCES mdl_config_revision(id),
    CONSTRAINT ck_kn_plan_state CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
