CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value CLOB NOT NULL,
    description VARCHAR(256),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

CREATE TABLE IF NOT EXISTS datasource (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    description VARCHAR(500),
    db_type VARCHAR(20),
    host VARCHAR(255),
    port INT,
    database_name VARCHAR(100),
    charset VARCHAR(20),
    status INT,
    health_status VARCHAR(20),
    creator_id BIGINT,
    deleted BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS datasource_secret (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    username VARCHAR(100),
    encrypted_password VARCHAR(500),
    encrypt_version INT,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS metadata_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    snapshot_version INT,
    schema_hash VARCHAR(64),
    status VARCHAR(30),
    quality_score DECIMAL(5,2),
    created_at DATETIME,
    updated_at DATETIME
);

INSERT INTO sys_config (config_key, config_value, description)
VALUES
    ('metadata.auto-sync.enabled', 'false', '元数据自动同步开关'),
    ('metadata.auto-sync.cron', '0 0 2 * * ?', '元数据自动同步 cron 表达式');

CREATE TABLE IF NOT EXISTS vector_index_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    metadata_snapshot_id BIGINT,
    knowledge_version_no INT,
    previous_version_no INT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    started_at DATETIME,
    finished_at DATETIME,
    error_message VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
