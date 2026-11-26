-- 原型允许 20 MiB 原件，扩充独立附件表的二进制容量。
ALTER TABLE ds_business_attachment
    DROP CHECK ck_ds_attachment_size,
    MODIFY COLUMN content LONGBLOB NOT NULL,
    ADD CONSTRAINT ck_ds_attachment_size CHECK (byte_size BETWEEN 1 AND 20971520);
