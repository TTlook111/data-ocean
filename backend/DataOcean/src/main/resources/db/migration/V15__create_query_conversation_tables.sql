-- =====================================================
-- V15: Create query task and conversation tables
-- =====================================================

-- 查询任务表：记录每次 NL2SQL 查询的状态和结果
CREATE TABLE IF NOT EXISTS query_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id VARCHAR(50) NOT NULL COMMENT '任务唯一标识（UUID）',
    user_id BIGINT NOT NULL COMMENT '提交用户 ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源 ID',
    question TEXT NOT NULL COMMENT '用户自然语言问题',
    rewritten_query TEXT COMMENT '改写后的结构化查询',
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '任务状态：PROCESSING/COMPLETED/FAILED/CANCELLED/TIMEOUT',
    result_sql TEXT COMMENT '最终生成的 SQL',
    sql_explanation TEXT COMMENT 'SQL 口径说明',
    result_data JSON COMMENT '查询结果数据（前 100 行）',
    result_columns JSON COMMENT '结果列元信息',
    chart_config JSON COMMENT 'ECharts 图表配置',
    used_tables JSON COMMENT '使用的表列表',
    used_columns JSON COMMENT '使用的字段列表',
    error_message TEXT COMMENT '错误信息',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    total_time_ms INT COMMENT '总耗时（毫秒）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at DATETIME COMMENT '完成时间',
    UNIQUE INDEX uk_task_id (task_id),
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NL2SQL 查询任务表';

-- 会话表：管理用户的对话会话
CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源 ID',
    title VARCHAR(200) COMMENT '会话标题（取首条问题前 50 字）',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE/ARCHIVED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status (user_id, status, updated_at DESC),
    INDEX idx_datasource (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';

-- 会话消息表：记录每轮对话的问答内容
CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    conversation_id BIGINT NOT NULL COMMENT '会话 ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：user/assistant',
    content TEXT NOT NULL COMMENT '消息内容',
    task_id VARCHAR(50) COMMENT '关联的查询任务 ID（assistant 消息）',
    metadata JSON COMMENT '附加元数据（SQL、图表等）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_conversation_order (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';
