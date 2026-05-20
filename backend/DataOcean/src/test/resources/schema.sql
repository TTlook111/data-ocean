CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value CLOB NOT NULL,
    description VARCHAR(256),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

INSERT INTO sys_config (config_key, config_value, description)
VALUES
    ('metadata.auto-sync.enabled', 'false', '元数据自动同步开关'),
    ('metadata.auto-sync.cron', '0 0 2 * * ?', '元数据自动同步 cron 表达式');
