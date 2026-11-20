-- 知识文档身份属于单一数据源，正文和事实引用随不可变修订保留。
CREATE TABLE kn_document (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(120) NOT NULL,
    head_revision_id BIGINT UNSIGNED NOT NULL,
    confirmed_revision_id BIGINT UNSIGNED NULL,
    confirmed_by BIGINT UNSIGNED NULL,
    confirmed_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_kn_document_source (source_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 章节、用途和实际边界均属于修订，不在聚合上覆盖历史。
CREATE TABLE kn_document_revision (
    id BIGINT UNSIGNED PRIMARY KEY,
    document_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    content JSON NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_kn_document_revision (document_id, revision),
    CONSTRAINT fk_kn_document_revision FOREIGN KEY (document_id) REFERENCES kn_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 固定实际确认编号、对象世代和结构身份，支持反查章节受影响范围。
CREATE TABLE kn_document_source_ref (
    revision_id BIGINT UNSIGNED NOT NULL,
    section_index INT UNSIGNED NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    object_id BIGINT UNSIGNED NOT NULL,
    generation INT UNSIGNED NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    fact_revision_id BIGINT UNSIGNED NOT NULL,
    structure_revision_id BIGINT UNSIGNED NOT NULL,
    selected BOOLEAN NOT NULL,
    label VARCHAR(720) NOT NULL,
    PRIMARY KEY (revision_id, section_index, kind, target_id),
    KEY ix_kn_fact_ref (source_id, kind, target_id, fact_revision_id),
    CONSTRAINT fk_kn_ref_revision FOREIGN KEY (revision_id) REFERENCES kn_document_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 人工确认独立追加，精确关联确认时阅读的正文。
CREATE TABLE kn_document_confirmation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT UNSIGNED NOT NULL,
    revision_id BIGINT UNSIGNED NOT NULL,
    confirmed_by BIGINT UNSIGNED NOT NULL,
    confirmed_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_kn_confirmation_document (document_id, id),
    CONSTRAINT fk_kn_confirmation_document FOREIGN KEY (document_id) REFERENCES kn_document(id),
    CONSTRAINT fk_kn_confirmation_revision FOREIGN KEY (revision_id) REFERENCES kn_document_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
