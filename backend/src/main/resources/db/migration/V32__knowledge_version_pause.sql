-- 使用限制独立于发布绑定，切换版本时同一范围仍受限制。
CREATE TABLE kn_release_pause (
    id BIGINT UNSIGNED PRIMARY KEY,
    scope_id BIGINT UNSIGNED NOT NULL,
    binding_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(300) NOT NULL,
    paused_by BIGINT UNSIGNED NOT NULL,
    paused_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    current_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    restore_reason VARCHAR(300) NULL,
    restored_by BIGINT UNSIGNED NULL,
    restored_at DATETIME(3) NULL,
    active_marker TINYINT GENERATED ALWAYS AS (IF(restored_at IS NULL,1,NULL)) STORED,
    UNIQUE KEY uk_kn_pause_active (scope_id,active_marker),
    CONSTRAINT fk_kn_pause_scope FOREIGN KEY (scope_id) REFERENCES kn_release_scope(id),
    CONSTRAINT fk_kn_pause_binding FOREIGN KEY (binding_id) REFERENCES kn_release_binding(id),
    CONSTRAINT fk_kn_pause_current_run FOREIGN KEY (current_run_id) REFERENCES kn_release_validation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 隔离验证单独记录使用目的和目标，不替换原发布验证指针。
ALTER TABLE kn_release_validation
    ADD COLUMN recovery_pause_id BIGINT UNSIGNED NULL,
    ADD COLUMN recovery_binding_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_kn_run_recovery_pause FOREIGN KEY (recovery_pause_id) REFERENCES kn_release_pause(id),
    ADD CONSTRAINT fk_kn_run_recovery_binding FOREIGN KEY (recovery_binding_id) REFERENCES kn_release_binding(id),
    ADD CONSTRAINT ck_kn_run_recovery_pair CHECK ((recovery_pause_id IS NULL) = (recovery_binding_id IS NULL));
