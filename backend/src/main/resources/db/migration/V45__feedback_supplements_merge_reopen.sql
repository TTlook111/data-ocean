-- 合并只建立当前的主从关系，原事项及问数证据继续独立留存。
ALTER TABLE fb_issue
    ADD COLUMN merged_into BIGINT UNSIGNED NULL,
    ADD COLUMN pre_merge_status VARCHAR(32) CHARACTER SET ascii NULL,
    ADD COLUMN completion_round INT UNSIGNED NOT NULL DEFAULT 1,
    ADD KEY ix_fb_issue_main (merged_into,id),
    ADD CONSTRAINT fk_fb_issue_main FOREIGN KEY (merged_into) REFERENCES fb_issue(id),
    ADD CONSTRAINT ck_fb_issue_merge CHECK (
        (status='MERGED' AND merged_into IS NOT NULL AND pre_merge_status IS NOT NULL)
        OR (status<>'MERGED' AND merged_into IS NULL AND pre_merge_status IS NULL)
    );

-- 补充始终归原事项和原问题，合并与纠正关系不移动正文。
CREATE TABLE fb_issue_supplement (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    issue_id BIGINT UNSIGNED NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    prompt VARCHAR(1000) NULL,
    answer VARCHAR(1000) NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    answered_at DATETIME(3) NULL,
    pending_request VARCHAR(36) GENERATED ALWAYS AS (IF(answered_at IS NULL,request_id,NULL)) STORED,
    UNIQUE KEY uk_fb_supplement_pending (pending_request),
    KEY ix_fb_supplement_issue (issue_id,answered_at,requested_at),
    KEY ix_fb_supplement_request (request_id,requested_at,id),
    CONSTRAINT fk_fb_supplement_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_supplement_request FOREIGN KEY (request_id) REFERENCES qry_request(id)
) ENGINE=InnoDB;

-- 办理事件保留关系另一端和原完成轮次，纠正合并与重开不会覆盖历史。
ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_kind,
    ADD COLUMN related_issue_id BIGINT UNSIGNED NULL,
    ADD COLUMN completion_round INT UNSIGNED NOT NULL DEFAULT 1,
    ADD CONSTRAINT fk_fb_activity_related_issue FOREIGN KEY (related_issue_id) REFERENCES fb_issue(id),
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED',
        'TASK_ADDED','TASK_ASSIGNED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','TASK_RESTORED',
        'SOURCE_FOLLOW_UP','SOURCE_WAITING','SUPPLEMENT_ASKED','SUPPLEMENT_ADDED',
        'MERGED','MEMBER_ADDED','UNMERGED','MEMBER_REMOVED','REOPENED'
    ));

-- 单人单轮次仍是一条回告，多个原问作为各自可核权的定位。
CREATE TABLE fb_notice_request (
    notice_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (notice_id,request_id),
    KEY ix_fb_notice_request (request_id,notice_id),
    CONSTRAINT fk_fb_notice_request_notice FOREIGN KEY (notice_id) REFERENCES fb_notice(id),
    CONSTRAINT fk_fb_notice_request_query FOREIGN KEY (request_id) REFERENCES qry_request(id)
) ENGINE=InnoDB;

INSERT INTO fb_notice_request(notice_id,request_id)
SELECT id,answer_id FROM fb_notice;
