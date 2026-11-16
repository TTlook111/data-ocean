-- 规则写入的回执与配置/修订同事务提交；唯一键防止响应丢失后的重复新增或重复修订。
-- 仅保存请求指纹和授权范围，不保存草稿正文；终结未知操作也占用同一操作标识。
CREATE TABLE quality_rule_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_user_id BIGINT NOT NULL COMMENT '发起账号；不依赖会话令牌',
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端冻结的操作 UUID',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规范化完整意图 SHA-256；无请求墓碑为空',
    action VARCHAR(16) NULL,
    source_rule_id BIGINT NULL,
    source_scope_type VARCHAR(16) NULL,
    source_datasource_id BIGINT NULL,
    target_scope_type VARCHAR(16) NULL,
    target_datasource_id BIGINT NULL,
    outcome VARCHAR(16) NOT NULL COMMENT 'APPLIED/REJECTED/CONFLICT；PENDING 仅在未提交事务中使用',
    result_rule_id BIGINT NULL,
    result_version INT NULL,
    error_code INT NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uk_quality_rule_operation (operator_user_id, operation_id),
    KEY idx_quality_rule_operation_result (result_rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量规则可靠写入回执';

-- 配置写频率低，使用规则领域单行事务锁保护跨实例启用互斥，不依赖数据库隔离级别的间隙锁。
-- 只协调配置写，不阻塞规则列表、规划或实际质量检查。
CREATE TABLE quality_rule_write_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB COMMENT='质量规则配置写事务锁';
INSERT INTO quality_rule_write_guard (id) VALUES (1);
