-- 独立任务、固定输入和一条表说明目标，验收可追溯真实调用。
CREATE TABLE gov_generation_task (
    request_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    command_id CHAR(36) CHARACTER SET ascii NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    object_id BIGINT UNSIGNED NOT NULL,
    snapshot_id BIGINT UNSIGNED NOT NULL,
    requested_by BIGINT UNSIGNED NOT NULL,
    authentication_version BIGINT UNSIGNED NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii NOT NULL,
    run_version INT UNSIGNED NOT NULL DEFAULT 1,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    lease_token CHAR(36) CHARACTER SET ascii NULL,
    due_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    started_at DATETIME(3) NULL,
    ended_at DATETIME(3) NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii NULL,
    UNIQUE KEY uk_gov_generation_command (requested_by,command_id),
    KEY ix_gov_dispatch (state,due_at,request_id),
    KEY ix_gov_generation_object (source_id,object_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 固定表说明目标及当时业务条目的并发版本。
CREATE TABLE gov_generation_target (
    request_id CHAR(36) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    base_item_version INT UNSIGNED NOT NULL,
    object_revision_id BIGINT UNSIGNED NOT NULL,
    content_paths JSON NOT NULL,
    CONSTRAINT fk_gov_generation_target_task FOREIGN KEY (request_id) REFERENCES gov_generation_task(request_id),
    CONSTRAINT fk_gov_generation_target_item FOREIGN KEY (item_id) REFERENCES gov_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 输入记录与资料防删除引用在任务接收事务共同写入。
CREATE TABLE gov_generation_input (
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    note_id BIGINT UNSIGNED NOT NULL,
    note_revision_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii NOT NULL,
    processed_at DATETIME(3) NULL,
    PRIMARY KEY (request_id,note_id),
    KEY ix_gov_generation_note (note_id,note_revision_id,state,request_id),
    CONSTRAINT fk_gov_generation_input_task FOREIGN KEY (request_id) REFERENCES gov_generation_task(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 记录实际模型名称和配置快照，仅保存调用诊断元数据。
CREATE TABLE gov_model_call (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    node_code VARCHAR(32) CHARACTER SET ascii NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    configuration_digest CHAR(64) CHARACTER SET ascii NOT NULL,
    provider_call_id VARCHAR(160) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_model_call_task (request_id),
    CONSTRAINT fk_gov_model_call_task FOREIGN KEY (request_id) REFERENCES gov_generation_task(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 原始建议独立于人工修订，模型重跑不会替代旧建议。
CREATE TABLE gov_suggestion (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    item_id BIGINT UNSIGNED NOT NULL,
    model_call_id BIGINT UNSIGNED NOT NULL,
    content JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_suggestion_target (request_id,item_id),
    CONSTRAINT fk_gov_suggestion_target FOREIGN KEY (request_id) REFERENCES gov_generation_target(request_id),
    CONSTRAINT fk_gov_suggestion_item FOREIGN KEY (item_id) REFERENCES gov_item(id),
    CONSTRAINT fk_gov_suggestion_call FOREIGN KEY (model_call_id) REFERENCES gov_model_call(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE gov_evidence_ref ADD CONSTRAINT fk_gov_evidence_suggestion FOREIGN KEY (suggestion_id) REFERENCES gov_suggestion(id);
ALTER TABLE gov_item_revision ADD CONSTRAINT fk_gov_revision_suggestion FOREIGN KEY (suggestion_id) REFERENCES gov_suggestion(id);
