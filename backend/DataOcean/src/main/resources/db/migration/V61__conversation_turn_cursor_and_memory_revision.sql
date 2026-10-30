-- A session has one active query turn; status DELETED is a terminal tombstone.
ALTER TABLE conversation
    ADD COLUMN active_turn_task_id VARCHAR(64) NULL COMMENT '当前会话唯一活动问数轮次',
    ADD INDEX idx_conversation_retention (status, updated_at),
    ADD INDEX idx_conversation_active_turn (active_turn_task_id);

ALTER TABLE conversation_message
    ADD INDEX idx_conversation_message_cursor (conversation_id, id);

ALTER TABLE conversation_context_summary
    ADD COLUMN permission_revision BIGINT NULL COMMENT '摘要最后核验时的数据授权修订';
