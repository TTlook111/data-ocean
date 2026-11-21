-- 每次逐题验证固定候选、构建代次、发起人与持久派发状态。
CREATE TABLE kn_release_validation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    candidate_id BIGINT UNSIGNED NOT NULL,
    build_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by BIGINT UNSIGNED NOT NULL,
    authentication_version BIGINT UNSIGNED NOT NULL,
    operation_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    connection_revision_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    poll_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    dispatch_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_kn_validation_poll (state,poll_at,id),
    KEY ix_kn_validation_candidate (candidate_id,created_at,id),
    CONSTRAINT fk_kn_validation_candidate FOREIGN KEY (candidate_id) REFERENCES kn_release_candidate(id),
    CONSTRAINT fk_kn_validation_build FOREIGN KEY (build_id) REFERENCES kn_release_build(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE kn_release_candidate ADD COLUMN current_validation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_kn_candidate_validation FOREIGN KEY (current_validation_id) REFERENCES kn_release_validation(id);

-- 执行凭据留在所属服务。存储确切题目审核、权限快照摘要及实测结果。
CREATE TABLE kn_release_validation_result (
    id BIGINT UNSIGNED PRIMARY KEY,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    test_revision_id BIGINT UNSIGNED NOT NULL,
    authentication_version BIGINT UNSIGNED NULL,
    access_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actual_count BIGINT UNSIGNED NULL,
    observed_chunks JSON NULL,
    statement_text VARCHAR(800) NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uk_kn_validation_question (run_id,test_revision_id),
    CONSTRAINT fk_kn_result_validation FOREIGN KEY (run_id) REFERENCES kn_release_validation(id),
    CONSTRAINT fk_kn_result_test_review FOREIGN KEY (test_revision_id) REFERENCES kn_test_review(revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 人工结论独立记录，原技术证据不改写且一轮一道题只能裁定一次。
CREATE TABLE kn_release_validation_judgment (
    result_id BIGINT UNSIGNED PRIMARY KEY,
    decision VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(300) NOT NULL,
    decided_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    CONSTRAINT fk_kn_judgment_result FOREIGN KEY (result_id) REFERENCES kn_release_validation_result(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
