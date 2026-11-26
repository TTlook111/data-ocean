-- 每次构建使用独立的索引代次；历史尝试不参与正式入口。
CREATE TABLE kn_release_build (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    candidate_id BIGINT UNSIGNED NOT NULL,
    collection_name VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by BIGINT UNSIGNED NOT NULL,
    authentication_version BIGINT UNSIGNED NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    next_poll_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    dispatch_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_claimed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    finished_at DATETIME(3) NULL,
    vector_dimension INT UNSIGNED NULL,
    verified_chunks INT UNSIGNED NULL,
    retrieval_ids JSON NULL,
    KEY ix_kn_build_poll (state, next_poll_at, id),
    KEY ix_kn_build_candidate (candidate_id, created_at, id),
    CONSTRAINT fk_kn_build_candidate FOREIGN KEY (candidate_id) REFERENCES kn_release_candidate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 每一完整章节固定正文、使用限制及确切治理证据映射。
CREATE TABLE kn_release_build_chunk (
    build_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    body MEDIUMTEXT NOT NULL,
    evidence JSON NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (build_id, ordinal),
    CONSTRAINT fk_kn_chunk_build FOREIGN KEY (build_id) REFERENCES kn_release_build(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE kn_release_candidate
    ADD COLUMN current_build_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_kn_candidate_current_build FOREIGN KEY (current_build_id) REFERENCES kn_release_build(id);
