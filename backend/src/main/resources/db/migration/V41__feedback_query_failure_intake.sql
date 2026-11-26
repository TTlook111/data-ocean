-- 终态与待派发标记同一次写入，不从已结束的历史请求补造反馈。
ALTER TABLE qry_request
    ADD COLUMN failure_phase VARCHAR(24) CHARACTER SET ascii NULL,
    ADD COLUMN failure_code VARCHAR(40) CHARACTER SET ascii NULL,
    ADD COLUMN failure_rule_signature CHAR(64) CHARACTER SET ascii NULL,
    ADD COLUMN failure_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_qry_failure_event (failure_event_id),
    ADD KEY ix_qry_failure_candidates (failure_event_id,failure_code,updated_at,id);

-- 所有事项引用原问，只有点踩事项需要已交付回答的确切修订。
ALTER TABLE fb_issue
    DROP FOREIGN KEY fk_fb_issue_answer,
    DROP CHECK ck_fb_issue_version,
    RENAME COLUMN origin_answer_id TO origin_request_id,
    MODIFY COLUMN origin_answer_revision INT UNSIGNED NULL,
    ADD COLUMN gap_key CHAR(64) CHARACTER SET ascii NULL,
    ADD COLUMN open_gap_key CHAR(64) CHARACTER SET ascii GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING_ASSIGNMENT','IN_PROGRESS','PENDING_VERIFICATION') THEN gap_key END
    ) STORED,
    ADD UNIQUE KEY uk_fb_issue_open_gap (source_id,open_gap_key),
    ADD KEY ix_fb_issue_origin_order (origin,updated_at,id),
    ADD CONSTRAINT fk_fb_issue_request FOREIGN KEY (origin_request_id) REFERENCES qry_request(id),
    ADD CONSTRAINT ck_fb_issue_context CHECK (version > 0 AND (
        (origin='USER_DISLIKE' AND origin_answer_revision IS NOT NULL AND origin_answer_revision > 0)
        OR (origin<>'USER_DISLIKE' AND origin_answer_revision IS NULL)
    ));

-- 问数失败证据和用户评价分开，重投按原问只建立一次关联。
CREATE TABLE fb_issue_query_evidence (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    issue_id BIGINT UNSIGNED NOT NULL,
    linked_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_fb_query_issue (issue_id,linked_at,request_id),
    CONSTRAINT fk_fb_query_evidence_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_query_evidence_request FOREIGN KEY (request_id) REFERENCES qry_request(id)
) ENGINE=InnoDB;
