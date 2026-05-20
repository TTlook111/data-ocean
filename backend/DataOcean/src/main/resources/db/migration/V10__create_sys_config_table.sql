-- 系统配置表，支持在线修改配置项（无需重启）
CREATE TABLE sys_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key  VARCHAR(128)  NOT NULL COMMENT '配置键',
    config_value TEXT         NOT NULL COMMENT '配置值',
    description VARCHAR(256)  DEFAULT NULL COMMENT '配置说明',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 初始化同步调度配置
INSERT INTO sys_config (config_key, config_value, description) VALUES
('metadata.auto-sync.enabled', 'false', '元数据自动同步开关'),
('metadata.auto-sync.cron', '0 0 2 * * ?', '元数据自动同步 cron 表达式');
