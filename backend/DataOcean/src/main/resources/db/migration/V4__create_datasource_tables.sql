CREATE TABLE IF NOT EXISTS datasource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    db_type VARCHAR(20) NOT NULL DEFAULT 'MYSQL',
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 3306,
    database_name VARCHAR(100) NOT NULL,
    charset VARCHAR(20) NOT NULL DEFAULT 'utf8mb4',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    health_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    creator_id BIGINT NOT NULL,
    deleted BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_host_port_db UNIQUE (host, port, database_name, deleted),
    INDEX idx_datasource_status (status),
    INDEX idx_datasource_health_status (health_status),
    INDEX idx_datasource_creator (creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS datasource_secret (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    encrypted_password VARCHAR(500) NOT NULL,
    encrypt_version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_datasource_secret_datasource UNIQUE (datasource_id),
    INDEX idx_datasource_secret_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS datasource_access (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    granted_by BIGINT NOT NULL,
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    CONSTRAINT uk_datasource_access_user UNIQUE (datasource_id, user_id),
    INDEX idx_datasource_access_user (user_id),
    INDEX idx_datasource_access_datasource (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS datasource_health_check (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    datasource_id BIGINT NULL,
    check_type VARCHAR(20) NOT NULL,
    success TINYINT NOT NULL,
    response_time_ms INT NULL,
    server_version VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_datasource_health_ds_time (datasource_id, checked_at DESC),
    INDEX idx_datasource_health_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('datasource:manage', '数据源管理', 'datasource', '创建、更新、删除和授权数据源')
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), module = VALUES(module), description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE permission_code = 'datasource:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
