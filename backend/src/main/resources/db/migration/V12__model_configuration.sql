-- 模型状态独立于内容修订，节点绑定按业务节点分别保存。
CREATE TABLE mdl_model (
    id BIGINT UNSIGNED PRIMARY KEY,
    revision_id BIGINT UNSIGNED NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT UNSIGNED NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 秘密按版本持久化，配置改名时继续引用原凭据，不复制密文。
CREATE TABLE mdl_credential_revision (
    id BIGINT UNSIGNED PRIMARY KEY,
    model_id BIGINT UNSIGNED NOT NULL,
    key_version VARCHAR(50) NOT NULL,
    ciphertext VARBINARY(8220) NOT NULL,
    FOREIGN KEY (model_id) REFERENCES mdl_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 历史配置只追加，旧调用可按真实修订直接回看。
CREATE TABLE mdl_config_revision (
    id BIGINT UNSIGNED PRIMARY KEY,
    model_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    protocol VARCHAR(32) CHARACTER SET ascii NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    timeout_seconds INT UNSIGNED NOT NULL,
    credential_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_mdl_config_revision (model_id, revision),
    FOREIGN KEY (model_id) REFERENCES mdl_model(id),
    FOREIGN KEY (credential_id) REFERENCES mdl_credential_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE mdl_node_assignment (
    node_code VARCHAR(40) CHARACTER SET ascii PRIMARY KEY,
    model_id BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    FOREIGN KEY (model_id) REFERENCES mdl_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO mdl_node_assignment (node_code) VALUES ('table-description');

-- 联调期记录保留既有摘要，新任务绑定真实配置修订和首次外发凭据。
ALTER TABLE gov_generation_task
    ADD COLUMN model_revision_id BIGINT UNSIGNED NULL,
    ADD COLUMN model_started_at DATETIME(3) NULL,
    ADD CONSTRAINT fk_gov_generation_model_revision FOREIGN KEY (model_revision_id) REFERENCES mdl_config_revision(id);

ALTER TABLE gov_model_call
    MODIFY COLUMN configuration_digest CHAR(64) CHARACTER SET ascii NULL,
    MODIFY COLUMN model_name VARCHAR(120) NULL,
    MODIFY COLUMN provider_call_id VARCHAR(160) NULL,
    ADD COLUMN state VARCHAR(20) CHARACTER SET ascii NOT NULL DEFAULT 'SUCCEEDED',
    ADD COLUMN failure_code VARCHAR(40) CHARACTER SET ascii NULL,
    ADD COLUMN elapsed_ms INT UNSIGNED NULL,
    ADD COLUMN input_tokens INT UNSIGNED NULL,
    ADD COLUMN output_tokens INT UNSIGNED NULL;
