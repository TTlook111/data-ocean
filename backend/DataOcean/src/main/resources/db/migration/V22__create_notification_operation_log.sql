-- ============================================================
-- 系统通知与操作日志表
-- ============================================================

-- 系统通知表
CREATE TABLE IF NOT EXISTS sys_notification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    type            VARCHAR(30)  NOT NULL COMMENT '通知类型：GOVERNANCE_ALERT/REVIEW_REMIND/SLOW_QUERY_ALERT/SYSTEM',
    title           VARCHAR(200) NOT NULL COMMENT '通知标题',
    content         VARCHAR(1000) NULL COMMENT '通知内容',
    target_user_id  BIGINT       NULL COMMENT '目标用户ID（NULL 表示全员通知）',
    is_read         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已读',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_notification_user (target_user_id),
    INDEX idx_notification_read (is_read),
    INDEX idx_notification_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统通知表';

-- 管理操作日志表
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    operator_id     BIGINT       NOT NULL COMMENT '操作人用户ID',
    operator_name   VARCHAR(50)  NULL COMMENT '操作人用户名',
    operation_type  VARCHAR(20)  NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE/QUERY/EXPORT',
    target_resource VARCHAR(100) NOT NULL COMMENT '目标资源（如 datasource、user、field-tag）',
    target_id       VARCHAR(50)  NULL COMMENT '目标资源ID',
    request_method  VARCHAR(10)  NOT NULL COMMENT 'HTTP 方法',
    request_path    VARCHAR(200) NOT NULL COMMENT '请求路径',
    request_params  TEXT         NULL COMMENT '请求参数（脱敏后）',
    execution_ms    INT          NULL COMMENT '执行耗时（毫秒）',
    is_success      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否成功',
    error_message   VARCHAR(500) NULL COMMENT '错误信息',
    ip_address      VARCHAR(50)  NULL COMMENT '客户端 IP',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_oplog_operator (operator_id),
    INDEX idx_oplog_resource (target_resource),
    INDEX idx_oplog_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理操作日志表';
