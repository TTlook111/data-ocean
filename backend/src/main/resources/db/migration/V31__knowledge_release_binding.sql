-- 单文档范围的正式清单，确切绑定内容、已验证索引、模型与发布依据。
CREATE TABLE kn_release_binding (
    id BIGINT UNSIGNED PRIMARY KEY,
    scope_id BIGINT UNSIGNED NOT NULL,
    candidate_id BIGINT UNSIGNED NOT NULL,
    build_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    validation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_revision_id BIGINT UNSIGNED NOT NULL,
    embedding_revision_id BIGINT UNSIGNED NOT NULL,
    previous_binding_id BIGINT UNSIGNED NULL,
    published_by BIGINT UNSIGNED NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    verified_at DATETIME(3) NULL,
    UNIQUE KEY uk_kn_binding_candidate (candidate_id),
    UNIQUE KEY uk_kn_binding_command (published_by,command_id),
    KEY ix_kn_binding_scope (scope_id,id),
    CONSTRAINT fk_kn_binding_scope FOREIGN KEY (scope_id) REFERENCES kn_release_scope(id),
    CONSTRAINT fk_kn_binding_candidate FOREIGN KEY (candidate_id) REFERENCES kn_release_candidate(id),
    CONSTRAINT fk_kn_binding_build FOREIGN KEY (build_id) REFERENCES kn_release_build(id),
    CONSTRAINT fk_kn_binding_validation FOREIGN KEY (validation_id) REFERENCES kn_release_validation(id),
    CONSTRAINT fk_kn_binding_document FOREIGN KEY (document_revision_id) REFERENCES kn_document_revision(id),
    CONSTRAINT fk_kn_binding_model FOREIGN KEY (embedding_revision_id) REFERENCES mdl_config_revision(id),
    CONSTRAINT fk_kn_binding_previous FOREIGN KEY (previous_binding_id) REFERENCES kn_release_binding(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 发布仅切换这一权威入口，索引和内容自身始终保持不可变。
ALTER TABLE kn_release_scope ADD CONSTRAINT fk_kn_scope_active_binding
    FOREIGN KEY (active_binding_id) REFERENCES kn_release_binding(id);
