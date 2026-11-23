-- 记录模型使用状态，避免用当前模型设置覆盖曾经的可用性。
CREATE TABLE mdl_model_use_event (
    model_id BIGINT UNSIGNED NOT NULL,
    model_version INT UNSIGNED NOT NULL,
    enabled BOOLEAN NOT NULL,
    effective_at DATETIME(3) NOT NULL,
    observed_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (model_id,model_version),
    KEY ix_mdl_use_time (model_id,effective_at,model_version),
    CONSTRAINT fk_mdl_use_model FOREIGN KEY (model_id) REFERENCES mdl_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 保存、编辑配置各占一个版本。版本数与配置数一致时，可以证明一直沿用初始启用状态。
INSERT INTO mdl_model_use_event(model_id,model_version,enabled,effective_at)
SELECT m.id,m.version,m.enabled,
    CASE WHEN m.enabled AND m.version=COUNT(r.id) THEN MIN(r.created_at) ELSE UTC_TIMESTAMP(3) END
FROM mdl_model m LEFT JOIN mdl_config_revision r ON r.model_id=m.id
GROUP BY m.id,m.version,m.enabled;

-- 旧自动结果以这次实际读取为观察基线，新结果写入真实记录时刻。
ALTER TABLE kn_release_validation_result ADD COLUMN observed_at DATETIME(3) NULL;
UPDATE kn_release_validation_result SET observed_at=UTC_TIMESTAMP(3) WHERE state<>'PENDING';

-- 运行题次和已核实发布都按原有事实时间读取。
ALTER TABLE kn_release_validation ADD KEY ix_kn_validation_created (created_at,id);
ALTER TABLE kn_publication_batch ADD KEY ix_kn_batch_verified (verified_at,source_id);
