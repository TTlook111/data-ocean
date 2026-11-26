-- 企业业务说明按数据源归属，内容和原文件在同一个平台事务中提交。
CREATE TABLE ds_business_note (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(140) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    revision INT UNSIGNED NOT NULL DEFAULT 1,
    state_reason VARCHAR(500) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_ds_note_source (source_id,state,updated_at,id),
    CONSTRAINT fk_ds_note_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT fk_ds_note_author FOREIGN KEY (author_id) REFERENCES iam_user(id),
    CONSTRAINT ck_ds_note_state CHECK (state IN ('ACTIVE','WITHDRAWN','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE ds_business_note_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    title VARCHAR(140) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    changed_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_ds_note_revision (note_id,revision),
    UNIQUE KEY uk_ds_note_revision_ref (note_id,id),
    CONSTRAINT fk_ds_note_revision_note FOREIGN KEY (note_id) REFERENCES ds_business_note(id),
    CONSTRAINT fk_ds_note_revision_user FOREIGN KEY (changed_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE ds_business_attachment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(180) NOT NULL,
    byte_size INT UNSIGNED NOT NULL,
    digest CHAR(64) CHARACTER SET ascii NOT NULL,
    content MEDIUMBLOB NOT NULL,
    UNIQUE KEY uk_ds_note_attachment_ref (note_id,id),
    CONSTRAINT fk_ds_attachment_note FOREIGN KEY (note_id) REFERENCES ds_business_note(id),
    CONSTRAINT ck_ds_attachment_size CHECK (byte_size BETWEEN 1 AND 5242880)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE ds_business_revision_attachment (
    note_id BIGINT UNSIGNED NOT NULL,
    revision_id BIGINT UNSIGNED NOT NULL,
    attachment_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (revision_id,attachment_id),
    CONSTRAINT fk_ds_note_blob_revision FOREIGN KEY (note_id,revision_id)
        REFERENCES ds_business_note_revision(note_id,id) ON DELETE CASCADE,
    CONSTRAINT fk_ds_note_blob_attachment FOREIGN KEY (note_id,attachment_id)
        REFERENCES ds_business_attachment(note_id,id)
) ENGINE=InnoDB;

-- 后续治理任务与成果按确切修订登记依赖，数据库约束阻止删除已引用修订。
CREATE TABLE ds_business_note_reference (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT UNSIGNED NOT NULL,
    revision_id BIGINT UNSIGNED NOT NULL,
    target_kind VARCHAR(32) CHARACTER SET ascii NOT NULL,
    target_id VARCHAR(40) CHARACTER SET ascii NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_ds_note_reference (revision_id,target_kind,target_id),
    CONSTRAINT fk_ds_note_ref_revision FOREIGN KEY (note_id,revision_id)
        REFERENCES ds_business_note_revision(note_id,id)
) ENGINE=InnoDB;
