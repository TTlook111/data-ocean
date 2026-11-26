-- 本次切换仅允许空附件表，阻止已有原件在迁移时丢失。
CREATE TEMPORARY TABLE ds_business_attachment_migration_check (
    existing_files BIGINT NOT NULL,
    CONSTRAINT ck_ds_migrate_empty_attachments CHECK (existing_files = 0) ENFORCED
);

INSERT INTO ds_business_attachment_migration_check (existing_files)
SELECT COUNT(*) FROM ds_business_attachment;

DROP TEMPORARY TABLE ds_business_attachment_migration_check;

-- 单独持久化上传预留与删除任务，使外部操作可跨进程重试。
CREATE TABLE IF NOT EXISTS ds_business_attachment_cleanup (
    object_bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ready_at DATETIME(3) NOT NULL,
    PRIMARY KEY (object_bucket,object_key),
    KEY ix_ds_attachment_cleanup_ready (object_bucket,ready_at,object_key)
) ENGINE=InnoDB;

-- 原件只存 OSS，表内保留不可变定位信息及原有校验摘要。
ALTER TABLE ds_business_attachment
    ADD COLUMN object_bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD COLUMN object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_ds_attachment_object (object_bucket,object_key),
    ADD CONSTRAINT ck_ds_attachment_oss_location CHECK (object_bucket <> '' AND object_key <> ''),
    DROP COLUMN content;
