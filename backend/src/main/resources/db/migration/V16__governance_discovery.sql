-- 发现任务复用治理队列，到模型调用前按单独业务节点固定修订。
ALTER TABLE gov_generation_task ADD COLUMN discovery_kind VARCHAR(12) CHARACTER SET ascii NULL;

INSERT INTO mdl_node_assignment (node_code) VALUES ('relation-discovery'), ('metric-discovery');

-- 候选的来源、身份和生成依据固定，重复发现沿用已存在的候选。
CREATE TABLE gov_discovery_candidate (
    id BIGINT UNSIGNED PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    object_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    identity_key CHAR(64) CHARACTER SET ascii NOT NULL,
    snapshot_id BIGINT UNSIGNED NOT NULL,
    evidence JSON NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    UNIQUE KEY uk_discovery_candidate (source_id,kind,identity_key),
    KEY ix_discovery_object (source_id,object_id,kind,id),
    CONSTRAINT fk_discovery_task FOREIGN KEY (request_id) REFERENCES gov_generation_task(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 两端表共用同一条关联，页面按对象查找而不复制规则。
CREATE TABLE gov_discovery_object (
    candidate_id BIGINT UNSIGNED NOT NULL,
    object_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (object_id,candidate_id),
    CONSTRAINT fk_discovery_object_candidate FOREIGN KEY (candidate_id) REFERENCES gov_discovery_candidate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 人工修订和审核事实逐次保存，始终可以回看原候选。
CREATE TABLE gov_discovery_revision (
    id BIGINT UNSIGNED PRIMARY KEY,
    candidate_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL,
    content JSON NOT NULL,
    dependencies JSON NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_discovery_revision (candidate_id,version),
    CONSTRAINT fk_discovery_candidate FOREIGN KEY (candidate_id) REFERENCES gov_discovery_candidate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
