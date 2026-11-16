-- 检查请求与已存在批次绑定；历史批次不补造请求标识，重复请求不会再次执行。
ALTER TABLE quality_check_batch
    ADD COLUMN operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN execution_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_quality_check_operation (created_by, operation_id);

-- 人工流转与分派使用客户端读到的版本；历史行从 1 开始，不伪造以前的版本次数。
ALTER TABLE metadata_quality_issue
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 1;
