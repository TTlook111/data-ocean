-- 人工处置结束等待，原问题、请求和实际答复仍各自留存。
ALTER TABLE fb_issue_supplement
    ADD COLUMN disposition VARCHAR(16) CHARACTER SET ascii NULL,
    ADD COLUMN disposition_reason VARCHAR(1000) NULL,
    ADD COLUMN disposed_by BIGINT UNSIGNED NULL,
    ADD COLUMN disposed_at DATETIME(3) NULL,
    MODIFY COLUMN pending_request VARCHAR(36)
        GENERATED ALWAYS AS (IF(answered_at IS NULL AND disposed_at IS NULL,request_id,NULL)) STORED,
    ADD CONSTRAINT fk_fb_supplement_disposer FOREIGN KEY (disposed_by) REFERENCES iam_user(id),
    ADD CONSTRAINT ck_fb_supplement_disposition CHECK (
        (disposition IS NULL AND disposition_reason IS NULL AND disposed_by IS NULL AND disposed_at IS NULL)
        OR (disposition IS NOT NULL AND disposition IN ('WITHDRAWN','UNAVAILABLE')
            AND disposition_reason IS NOT NULL AND disposed_by IS NOT NULL AND disposed_at IS NOT NULL
            AND answered_at IS NULL)
    );

ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_kind,
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED',
        'TASK_ADDED','TASK_ASSIGNED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','TASK_RESTORED',
        'SOURCE_FOLLOW_UP','SOURCE_WAITING','SUPPLEMENT_ASKED','SUPPLEMENT_ADDED','SUPPLEMENT_ENDED',
        'MERGED','MEMBER_ADDED','UNMERGED','MEMBER_REMOVED','REOPENED'
    ));
