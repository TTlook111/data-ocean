-- 每次测试绑定不可变配置修订，旧结果不会显示为新配置的测试结果。
CREATE TABLE mdl_connection_test (
    id BIGINT UNSIGNED NOT NULL,
    revision_id BIGINT UNSIGNED NOT NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii,
    tested_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_mdl_connection_revision (revision_id, id),
    CONSTRAINT fk_mdl_connection_revision FOREIGN KEY (revision_id) REFERENCES mdl_config_revision (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
