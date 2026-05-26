-- ============================================================
-- 血缘与审计模块 - 审计日志和血缘表
-- 包含：query_audit_log、query_lineage_table、query_lineage_column、llm_usage_log
-- ============================================================

-- 查询审计日志表：记录每次查询的完整生命周期
CREATE TABLE IF NOT EXISTS query_audit_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    query_task_id     BIGINT       NOT NULL COMMENT '关联的查询任务ID',
    user_id           BIGINT       NOT NULL COMMENT '查询用户ID',
    datasource_id     BIGINT       NOT NULL COMMENT '数据源ID',
    question          TEXT         NOT NULL COMMENT '用户自然语言问题',
    sql_text          TEXT         NULL COMMENT '最终生成的 SQL',
    used_tables       JSON         NULL COMMENT '使用的表列表（JSON 数组）',
    used_fields       JSON         NULL COMMENT '使用的字段列表（JSON 数组）',
    execution_time_ms INT          NULL COMMENT '总执行耗时（毫秒）',
    row_count         INT          NULL COMMENT '返回行数',
    is_success        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否成功（1=成功，0=失败）',
    error_message     VARCHAR(1000) NULL COMMENT '错误信息',
    is_slow           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否慢查询（1=是）',
    user_feedback     VARCHAR(10)  NULL COMMENT '用户反馈：LIKE/DISLIKE',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_audit_user (user_id),
    INDEX idx_audit_datasource (datasource_id),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_slow (is_slow),
    INDEX idx_audit_task (query_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询审计日志表';

-- 查询血缘-表级关系表
CREATE TABLE IF NOT EXISTS query_lineage_table (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    query_task_id   BIGINT       NOT NULL COMMENT '关联的查询任务ID',
    source_table    VARCHAR(200) NOT NULL COMMENT '源表名',
    target_name     VARCHAR(200) NULL COMMENT '目标名称（别名或结果集名）',
    relation_type   VARCHAR(20)  NOT NULL COMMENT '关系类型：FROM/JOIN/SUBQUERY',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_lineage_table_task (query_task_id),
    INDEX idx_lineage_table_source (source_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询血缘-表级关系表';

-- 查询血缘-字段级关系表
CREATE TABLE IF NOT EXISTS query_lineage_column (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    query_task_id   BIGINT       NOT NULL COMMENT '关联的查询任务ID',
    source_table    VARCHAR(200) NOT NULL COMMENT '源表名',
    source_column   VARCHAR(200) NOT NULL COMMENT '源字段名',
    expression      VARCHAR(500) NULL COMMENT '字段表达式（如聚合函数）',
    alias_name      VARCHAR(200) NULL COMMENT '别名',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_lineage_col_task (query_task_id),
    INDEX idx_lineage_col_source (source_table, source_column)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询血缘-字段级关系表';

-- LLM 调用日志表
CREATE TABLE IF NOT EXISTS llm_usage_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    query_task_id     BIGINT       NULL COMMENT '关联的查询任务ID',
    provider          VARCHAR(30)  NOT NULL DEFAULT 'QWEN' COMMENT 'LLM 提供商',
    model             VARCHAR(50)  NOT NULL COMMENT '模型名称',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT 'Prompt Token 数',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT 'Completion Token 数',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '总 Token 数',
    cost_amount       DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '费用（单位：元）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_llm_task (query_task_id),
    INDEX idx_llm_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 调用日志表';
