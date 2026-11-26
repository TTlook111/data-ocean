-- 后续活动保存业务状态，已有活动的历史状态仍保持未知。
ALTER TABLE fb_issue_activity
    ADD COLUMN status_after VARCHAR(32) CHARACTER SET ascii NULL;

-- 保存启用统计时的真实状态，为后续期末快照提供起点。
CREATE TABLE fb_issue_overview_baseline (
    issue_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    issue_version INT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii NOT NULL,
    captured_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_fb_overview_issue FOREIGN KEY (issue_id) REFERENCES fb_issue(id)
);

INSERT INTO fb_issue_overview_baseline(issue_id,issue_version,status,captured_at)
SELECT i.id,i.version,
    CASE WHEN i.merged_into IS NULL AND EXISTS (
        SELECT 1 FROM fb_issue_supplement followup
        JOIN fb_issue origin_issue ON origin_issue.id=followup.issue_id
        WHERE (origin_issue.id=i.id OR origin_issue.merged_into=i.id) AND followup.pending_request IS NOT NULL
    ) THEN 'PENDING_SUPPLEMENT' ELSE i.status END,
    UTC_TIMESTAMP(3)
FROM fb_issue i;
