-- 题目稳定归属文档，编辑和审核分别固定自己的正文修订。
CREATE TABLE kn_test_case (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    document_id BIGINT UNSIGNED NOT NULL,
    question VARCHAR(300) NOT NULL,
    head_revision_id BIGINT UNSIGNED NOT NULL,
    reviewed_revision_id BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    KEY ix_kn_case_document (source_id, document_id, id),
    CONSTRAINT fk_kn_case_document FOREIGN KEY (document_id) REFERENCES kn_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 预期和比较条件只属于事先固定的审核题；实际结果由发布运行另存。
CREATE TABLE kn_test_case_revision (
    id BIGINT UNSIGNED PRIMARY KEY,
    test_case_id BIGINT UNSIGNED NOT NULL,
    document_revision_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    content JSON NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_kn_case_revision (test_case_id, revision),
    CONSTRAINT fk_kn_case_revision FOREIGN KEY (test_case_id) REFERENCES kn_test_case(id),
    CONSTRAINT fk_kn_case_basis FOREIGN KEY (document_revision_id) REFERENCES kn_document_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 实际审核人及本次审核固定的验证身份。
CREATE TABLE kn_test_review (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    test_case_id BIGINT UNSIGNED NOT NULL,
    revision_id BIGINT UNSIGNED NOT NULL,
    reviewed_by BIGINT UNSIGNED NOT NULL,
    executor_id BIGINT UNSIGNED NOT NULL,
    reviewed_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_kn_test_review_revision (revision_id),
    CONSTRAINT fk_kn_review_case FOREIGN KEY (test_case_id) REFERENCES kn_test_case(id),
    CONSTRAINT fk_kn_review_revision FOREIGN KEY (revision_id) REFERENCES kn_test_case_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
