-- 伴随真实查询许可固定实际引用，旧轮次保留缺失状态。
ALTER TABLE qry_request ADD COLUMN usage_refs JSON NULL;

-- 业务评价按回答独立追加，原因文本可依批准策略单独处置。
CREATE TABLE fb_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    answer_id VARCHAR(36) NOT NULL,
    answer_revision INT UNSIGNED NOT NULL,
    sequence INT UNSIGNED NOT NULL,
    previous_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NULL,
    reason_purged_at DATETIME(3) NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_fb_event_sequence (user_id,answer_id,answer_revision,sequence),
    UNIQUE KEY uk_fb_event_command (user_id,command_id),
    KEY ix_fb_event_reconcile (action,received_at,id),
    CONSTRAINT fk_fb_event_owner FOREIGN KEY (user_id) REFERENCES iam_user(id),
    CONSTRAINT fk_fb_event_answer FOREIGN KEY (answer_id) REFERENCES qry_request(id),
    CONSTRAINT fk_fb_event_previous FOREIGN KEY (previous_event_id) REFERENCES fb_event(id),
    CONSTRAINT ck_fb_event_sequence CHECK (answer_revision > 0 AND sequence > 0),
    CONSTRAINT ck_fb_event_action CHECK (action IN ('LIKE','DISLIKE','REVOKE')),
    CONSTRAINT ck_fb_event_reason CHECK (
        (action = 'DISLIKE' AND (
            (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0 AND reason_purged_at IS NULL)
            OR (reason IS NULL AND reason_purged_at IS NOT NULL)))
        OR (action <> 'DISLIKE' AND reason IS NULL AND reason_purged_at IS NULL)
    )
) ENGINE=InnoDB;

-- 当前摘要可重建，撤销也固定最后事件和并发版本。
CREATE TABLE fb_current_rating (
    user_id BIGINT UNSIGNED NOT NULL,
    answer_id VARCHAR(36) NOT NULL,
    answer_revision INT UNSIGNED NOT NULL,
    last_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rating VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT UNSIGNED NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id,answer_id,answer_revision),
    CONSTRAINT fk_fb_current_owner FOREIGN KEY (user_id) REFERENCES iam_user(id),
    CONSTRAINT fk_fb_current_answer FOREIGN KEY (answer_id) REFERENCES qry_request(id),
    CONSTRAINT fk_fb_current_event FOREIGN KEY (last_event_id) REFERENCES fb_event(id),
    CONSTRAINT ck_fb_current_version CHECK (answer_revision > 0 AND version > 0),
    CONSTRAINT ck_fb_current_rating CHECK (rating IN ('NONE','LIKE','DISLIKE'))
) ENGINE=InnoDB;

-- 待分派事项以原回答去重，保留独立的状态与处理版本。
CREATE TABLE fb_issue (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    origin VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    origin_answer_id VARCHAR(36) NOT NULL,
    origin_answer_revision INT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assignee_id BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_fb_issue_origin (origin,origin_answer_id,origin_answer_revision),
    KEY ix_fb_issue_order (updated_at,id),
    KEY ix_fb_issue_source_order (source_id,updated_at,id),
    CONSTRAINT fk_fb_issue_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT fk_fb_issue_answer FOREIGN KEY (origin_answer_id) REFERENCES qry_request(id),
    CONSTRAINT fk_fb_issue_assignee FOREIGN KEY (assignee_id) REFERENCES iam_user(id),
    CONSTRAINT ck_fb_issue_version CHECK (origin_answer_revision > 0 AND version > 0)
) ENGINE=InnoDB;

-- 每条实际点踩都保留为原始线索，事件重投不产生重复关联。
CREATE TABLE fb_issue_evidence (
    feedback_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    issue_id BIGINT UNSIGNED NOT NULL,
    linked_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_fb_evidence_issue (issue_id,linked_at,feedback_event_id),
    CONSTRAINT fk_fb_evidence_event FOREIGN KEY (feedback_event_id) REFERENCES fb_event(id),
    CONSTRAINT fk_fb_evidence_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id)
) ENGINE=InnoDB;
