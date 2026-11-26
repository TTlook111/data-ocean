-- 首轮发布范围由同源单篇文档确定，基础绑定留空表示尚未发布。
CREATE TABLE kn_release_scope (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    document_id BIGINT UNSIGNED NOT NULL,
    active_binding_id BIGINT UNSIGNED NULL,
    UNIQUE KEY uk_kn_release_scope (source_id, document_id),
    CONSTRAINT fk_kn_release_scope_document FOREIGN KEY (document_id) REFERENCES kn_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 候选固定不可变的确认正文及章节引用、基础绑定和向量配置修订。
CREATE TABLE kn_release_candidate (
    id BIGINT UNSIGNED PRIMARY KEY,
    scope_id BIGINT UNSIGNED NOT NULL,
    base_binding_id BIGINT UNSIGNED NULL,
    document_revision_id BIGINT UNSIGNED NOT NULL,
    embedding_revision_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(120) NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PREPARED',
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_kn_release_candidate_scope (scope_id, id),
    CONSTRAINT fk_kn_release_candidate_scope FOREIGN KEY (scope_id) REFERENCES kn_release_scope(id),
    CONSTRAINT fk_kn_release_candidate_document FOREIGN KEY (document_revision_id) REFERENCES kn_document_revision(id),
    CONSTRAINT fk_kn_release_candidate_model FOREIGN KEY (embedding_revision_id) REFERENCES mdl_config_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 引用实际审核事实，题单后续编辑不覆盖本候选的判定内容和执行身份。
CREATE TABLE kn_release_candidate_test (
    candidate_id BIGINT UNSIGNED NOT NULL,
    test_revision_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (candidate_id, test_revision_id),
    CONSTRAINT fk_kn_release_test_candidate FOREIGN KEY (candidate_id) REFERENCES kn_release_candidate(id),
    CONSTRAINT fk_kn_release_test_review FOREIGN KEY (test_revision_id) REFERENCES kn_test_review(revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
