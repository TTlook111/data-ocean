-- 区分原始命令的显式条件与运行时解析条件，并保护等价执行。
ALTER TABLE qry_request
    ADD COLUMN command_fingerprint BINARY(32) NULL,
    ADD COLUMN execution_fingerprint BINARY(32) NULL,
    ADD KEY idx_qry_execution_fingerprint (user_id,source_id,execution_fingerprint);
