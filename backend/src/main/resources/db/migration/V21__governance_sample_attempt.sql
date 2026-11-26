-- 读取前保留持久额度，原始样本值不落库。
CREATE TABLE gov_sample_attempt (
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    attempt INT UNSIGNED NOT NULL,
    field_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii NOT NULL,
    statement_sql VARCHAR(1000) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    returned_rows INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    ended_at DATETIME(3) NULL,
    PRIMARY KEY (request_id,attempt),
    CONSTRAINT fk_gov_sample_task FOREIGN KEY (request_id) REFERENCES gov_generation_task(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
