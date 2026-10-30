-- Per-candidate idempotency record. Execution bindings and raw rows are never stored here.
CREATE TABLE query_attempt (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                         VARCHAR(64) NOT NULL,
    attempt_id                      VARCHAR(64) NOT NULL,
    attempt_no                      INT NOT NULL,
    sql_hash                        CHAR(64) NOT NULL,
    safe_sql                        MEDIUMTEXT NULL COMMENT '可展示 SQL；字符串字面量已脱敏',
    status                          VARCHAR(24) NOT NULL COMMENT 'AUTHORIZED/EXECUTING/PROTECTED/UNCERTAIN/REJECTED',
    permission_revision             BIGINT NOT NULL,
    active_metadata_snapshot_id     BIGINT NOT NULL,
    resource_request_json           JSON NOT NULL COMMENT '已授权的表字段及 AST 使用证据；不含记录参数值',
    execution_snapshot_json         JSON NOT NULL COMMENT '安全 S1 快照；不含执行绑定值',
    protected_data                  JSON NULL COMMENT 'Java 最终脱敏后的行；Python 原始结果不落库',
    protected_columns               JSON NULL,
    source_trace                    JSON NULL,
    masked_fields                   JSON NULL,
    used_tables                     JSON NULL,
    used_columns                    JSON NULL,
    row_count                       INT NULL,
    execution_time_ms               INT NULL,
    error_message                   VARCHAR(500) NULL,
    created_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at                    DATETIME NULL,
    UNIQUE KEY uk_query_attempt_id (task_id, attempt_id),
    UNIQUE KEY uk_query_attempt_number (task_id, attempt_no),
    KEY idx_query_attempt_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='S1 SQL 候选逐次授权、执行与受保护结果幂等记录';

ALTER TABLE query_task
    ADD COLUMN llm_call_count TINYINT NOT NULL DEFAULT 0 COMMENT 'G0 冻结的模型调用预算计数',
    ADD COLUMN embedding_call_count TINYINT NOT NULL DEFAULT 0 COMMENT 'S1 每问最多两次授权 RAG embedding',
    ADD COLUMN estimated_ai_cost_cny DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '当前任务累计模型费用估算';

CREATE TABLE query_model_call (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                 VARCHAR(64) NOT NULL,
    call_id                 VARCHAR(128) NOT NULL,
    node_name               VARCHAR(48) NOT NULL,
    model_name              VARCHAR(100) NOT NULL,
    status                  VARCHAR(20) NOT NULL COMMENT 'RESERVED/COMPLETED/AMBIGUOUS',
    input_tokens            INT NOT NULL,
    output_tokens           INT NOT NULL,
    reserved_cost_cny       DECIMAL(10,6) NOT NULL,
    actual_cost_cny         DECIMAL(10,6) NULL,
    usage_estimated         TINYINT NOT NULL DEFAULT 0,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            DATETIME NULL,
    UNIQUE KEY uk_query_model_call (task_id, call_id),
    KEY idx_query_model_call_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LangGraph 持久化模型预算预留与结算';
