-- 当期处理结果和人工复验使用事项版本作为不可变引用。
ALTER TABLE fb_issue
    ADD COLUMN result_version INT UNSIGNED NULL,
    ADD COLUMN verification_version INT UNSIGNED NULL;

ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_action,
    ADD COLUMN conclusion VARCHAR(1000) NULL,
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED'
    )),
    ADD CONSTRAINT ck_fb_activity_conclusion CHECK (action='CLOSED' OR conclusion IS NULL);

-- 技术处理结果与复验各自留存，转派和修订只改变当期引用。
CREATE TABLE fb_issue_result (
    issue_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    impact VARCHAR(20) CHARACTER SET ascii NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    check_method VARCHAR(1000) NOT NULL,
    reference_text VARCHAR(500) NULL,
    regression VARCHAR(1000) NULL,
    submitted_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (issue_id,version),
    CONSTRAINT fk_fb_result_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_result_author FOREIGN KEY (author_id) REFERENCES iam_user(id),
    CONSTRAINT ck_fb_result_impact CHECK (impact IN ('DISPLAY','CONNECTION','QUERY_SAFETY'))
) ENGINE=InnoDB;

CREATE TABLE fb_issue_verification (
    issue_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL,
    result_version INT UNSIGNED NOT NULL,
    verifier_id BIGINT UNSIGNED NOT NULL,
    context_signature CHAR(64) CHARACTER SET ascii NOT NULL,
    decision VARCHAR(8) CHARACTER SET ascii NOT NULL,
    test_scope VARCHAR(1000) NOT NULL,
    expected VARCHAR(500) NOT NULL,
    actual VARCHAR(1000) NOT NULL,
    data_basis VARCHAR(1000) NOT NULL,
    verified_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (issue_id,version),
    CONSTRAINT fk_fb_verify_result FOREIGN KEY (issue_id,result_version) REFERENCES fb_issue_result(issue_id,version),
    CONSTRAINT fk_fb_verify_actor FOREIGN KEY (verifier_id) REFERENCES iam_user(id),
    CONSTRAINT ck_fb_verify_decision CHECK (decision IN ('PASSED','FAILED'))
) ENGINE=InnoDB;

-- 回告只保存模板编码和稳定引用，与自由结论和内部证据分开。
CREATE TABLE fb_notice (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    issue_id BIGINT UNSIGNED NOT NULL,
    completion_round INT UNSIGNED NOT NULL,
    recipient_id BIGINT UNSIGNED NOT NULL,
    answer_id VARCHAR(36) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    template VARCHAR(24) CHARACTER SET ascii NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'QUEUED',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    sent_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,
    UNIQUE KEY uk_fb_notice_completion (issue_id,completion_round,recipient_id),
    KEY ix_fb_notice_recipient (recipient_id,state,sent_at,id),
    CONSTRAINT fk_fb_notice_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_notice_recipient FOREIGN KEY (recipient_id) REFERENCES iam_user(id),
    CONSTRAINT fk_fb_notice_answer FOREIGN KEY (answer_id) REFERENCES qry_request(id),
    CONSTRAINT fk_fb_notice_source FOREIGN KEY (source_id) REFERENCES ds_source(id),
    CONSTRAINT ck_fb_notice_state CHECK (state IN ('QUEUED','DELIVERED','RESTRICTED','SUPPRESSED')),
    CONSTRAINT ck_fb_notice_template CHECK (template IN ('RESOLVED','DEFINITION_CONFIRMED','NOT_REPRODUCED','CANCELLED','OTHER'))
) ENGINE=InnoDB;
