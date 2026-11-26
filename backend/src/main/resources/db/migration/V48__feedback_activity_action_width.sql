-- 技术跟进动作编码长于原动作列，扩容后继续保留原约束和历史。
ALTER TABLE fb_issue_activity
    MODIFY COLUMN action VARCHAR(24) CHARACTER SET ascii NOT NULL;
