-- 输出限制共享当前正文，批准记录保存办结时的规则快照。
CREATE TABLE access_output_rule (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_access_rule_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_responsibility (
    source_id BIGINT UNSIGNED PRIMARY KEY,
    primary_user_id BIGINT UNSIGNED NOT NULL,
    alternate_user_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    CONSTRAINT fk_access_resp_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT fk_access_resp_primary FOREIGN KEY (primary_user_id) REFERENCES iam_user(id),
    CONSTRAINT fk_access_resp_alternate FOREIGN KEY (alternate_user_id) REFERENCES iam_user(id),
    CONSTRAINT ck_access_resp_distinct CHECK (primary_user_id <> alternate_user_id)
) ENGINE=InnoDB;

CREATE TABLE access_request (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    applicant_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    data_needed VARCHAR(2000) NOT NULL,
    supplement VARCHAR(1000) NULL,
    joint BOOLEAN NOT NULL DEFAULT FALSE,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    submitted_at DATETIME(3) NULL,
    KEY ix_access_request_applicant (applicant_id, id),
    CONSTRAINT fk_access_request_user FOREIGN KEY (applicant_id) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_request_item (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT UNSIGNED NOT NULL,
    source_id BIGINT UNSIGNED NULL,
    state VARCHAR(24) CHARACTER SET ascii NOT NULL,
    rule_ids JSON NULL,
    rule_snapshot JSON NULL,
    handled_by BIGINT UNSIGNED NULL,
    handled_at DATETIME(3) NULL,
    reason VARCHAR(1000) NULL,
    UNIQUE KEY uk_access_item_source (request_id, source_id),
    KEY ix_access_item_queue (state, source_id, request_id),
    CONSTRAINT fk_access_item_request FOREIGN KEY (request_id) REFERENCES access_request(id),
    CONSTRAINT fk_access_item_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT fk_access_item_handler FOREIGN KEY (handled_by) REFERENCES iam_user(id),
    CONSTRAINT ck_access_item_state CHECK (state IN (
        'DRAFT','PENDING','NEEDS_SUPPLEMENT','APPROVED','REJECTED','WITHDRAWN','EFFECTIVE','ENDED'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_request_revision (
    request_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    snapshot JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (request_id, version),
    CONSTRAINT fk_access_revision_request FOREIGN KEY (request_id) REFERENCES access_request(id),
    CONSTRAINT fk_access_revision_actor FOREIGN KEY (actor_id) REFERENCES iam_user(id)
) ENGINE=InnoDB;

CREATE TABLE access_request_item_action (
    item_id BIGINT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (item_id, actor_id),
    KEY ix_access_action_actor (actor_id, item_id),
    CONSTRAINT fk_access_item_action_item FOREIGN KEY (item_id) REFERENCES access_request_item(id),
    CONSTRAINT fk_access_item_action_actor FOREIGN KEY (actor_id) REFERENCES iam_user(id)
) ENGINE=InnoDB;

CREATE TABLE access_department_source (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT UNSIGNED NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    rule_ids JSON NOT NULL,
    rule_snapshot JSON NOT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    ended_at DATETIME(3) NULL,
    active_source_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(ended_at IS NULL, source_id, NULL)) STORED,
    UNIQUE KEY uk_access_department_active (department_id, active_source_id),
    CONSTRAINT fk_access_dep_department FOREIGN KEY (department_id) REFERENCES iam_department(id),
    CONSTRAINT fk_access_dep_source FOREIGN KEY (source_id) REFERENCES ds_source(id)
) ENGINE=InnoDB;

CREATE TABLE access_grant (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    ended_at DATETIME(3) NULL,
    ended_by BIGINT UNSIGNED NULL,
    end_reason VARCHAR(500) NULL,
    UNIQUE KEY uk_access_grant_item (item_id),
    CONSTRAINT fk_access_grant_item FOREIGN KEY (item_id) REFERENCES access_request_item(id),
    CONSTRAINT fk_access_grant_ender FOREIGN KEY (ended_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
