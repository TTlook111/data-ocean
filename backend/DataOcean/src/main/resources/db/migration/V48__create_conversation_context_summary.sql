-- ============================================================
-- V48: 会话长期上下文摘要
-- Java 持久化摘要，Python 只负责按请求生成摘要，不保存会话状态。
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_context_summary (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    conversation_id     BIGINT NOT NULL COMMENT '会话ID',
    summary_json        JSON NOT NULL COMMENT '结构化会话摘要 JSON',
    covered_message_id  BIGINT NULL COMMENT '摘要已覆盖的最后一条消息ID',
    summary_version     INT NOT NULL DEFAULT 1 COMMENT '摘要版本号',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_conversation_context_summary (conversation_id),
    INDEX idx_summary_covered_message (conversation_id, covered_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话长期上下文摘要';
