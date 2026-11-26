-- 处理任务使用创建时的事项版本作为事项内编号，历史结果和复验仍沿用原台账。
CREATE TABLE fb_issue_task (
    issue_id BIGINT UNSIGNED NOT NULL,
    task_id INT UNSIGNED NOT NULL,
    direction VARCHAR(24) CHARACTER SET ascii NOT NULL,
    assignee_id BIGINT UNSIGNED NOT NULL,
    scope_text VARCHAR(1000) NULL,
    status VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'IN_PROGRESS',
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    result_version INT UNSIGNED NULL,
    verification_version INT UNSIGNED NULL,
    recheck_after DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (issue_id,task_id),
    KEY ix_fb_task_assignee (assignee_id,status,issue_id),
    CONSTRAINT fk_fb_task_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_task_assignee FOREIGN KEY (assignee_id) REFERENCES iam_user(id),
    CONSTRAINT fk_fb_task_result FOREIGN KEY (issue_id,result_version) REFERENCES fb_issue_result(issue_id,version),
    CONSTRAINT fk_fb_task_verification FOREIGN KEY (issue_id,verification_version) REFERENCES fb_issue_verification(issue_id,version),
    CONSTRAINT ck_fb_task_direction CHECK (direction IN ('KNOWLEDGE','TECHNICAL')),
    CONSTRAINT ck_fb_task_status CHECK (status IN ('IN_PROGRESS','SUBMITTED','NO_CHANGE','CANCELLED')),
    CONSTRAINT ck_fb_task_result CHECK (status <> 'SUBMITTED' OR result_version IS NOT NULL)
) ENGINE=InnoDB;

-- 每个任务动作与现有操作记录一起展示，历史范围和复验仍可按版本回看。
ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_kind,
    DROP CHECK ck_fb_activity_root,
    MODIFY COLUMN root_cause VARCHAR(1000) NULL,
    ADD COLUMN task_id INT UNSIGNED NULL,
    ADD CONSTRAINT fk_fb_activity_task FOREIGN KEY (issue_id,task_id) REFERENCES fb_issue_task(issue_id,task_id),
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED',
        'TASK_ADDED','TASK_ASSIGNED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','TASK_RESTORED'
    )),
    ADD CONSTRAINT ck_fb_activity_root CHECK (
        action IN ('REVIEWED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED') OR root_cause IS NULL
    );
