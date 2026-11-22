-- 既有入队事项保持待分派，初步方向可以保留待核实。
ALTER TABLE fb_issue
    ADD COLUMN direction VARCHAR(24) CHARACTER SET ascii NOT NULL DEFAULT 'UNDETERMINED',
    ADD KEY ix_fb_issue_assignee (assignee_id,status,updated_at,id),
    ADD CONSTRAINT ck_fb_issue_direction CHECK (direction IN (
        'UNDETERMINED','KNOWLEDGE','TECHNICAL','SOURCE_DATA','DEFINITION','ACCESS'
    ));

-- 分派和核查按事项版本追加，敏感正文与普通审计分开读取。
CREATE TABLE fb_issue_activity (
    issue_id BIGINT UNSIGNED NOT NULL,
    issue_version INT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii NOT NULL,
    assignee_id BIGINT UNSIGNED NULL,
    direction VARCHAR(24) CHARACTER SET ascii NULL,
    note VARCHAR(1000) NOT NULL,
    root_cause VARCHAR(500) NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (issue_id,issue_version),
    CONSTRAINT fk_fb_activity_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id),
    CONSTRAINT fk_fb_activity_actor FOREIGN KEY (actor_id) REFERENCES iam_user(id),
    CONSTRAINT fk_fb_activity_assignee FOREIGN KEY (assignee_id) REFERENCES iam_user(id),
    CONSTRAINT ck_fb_activity_action CHECK (action IN ('ASSIGNED','REVIEWED')),
    CONSTRAINT ck_fb_activity_root CHECK (action='REVIEWED' OR root_cause IS NULL)
) ENGINE=InnoDB;
