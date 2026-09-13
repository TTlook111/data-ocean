-- 轨道 A 浏览器验收的辅助数据源夹具
-- 用于双数据源上下文隔离和浏览器创建数据源场景。

CREATE DATABASE IF NOT EXISTS dataocean_acceptance_alt
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dataocean_demo_alt'@'%' IDENTIFIED BY 'DataOceanDemoAlt@123';
ALTER USER 'dataocean_demo_alt'@'%' IDENTIFIED BY 'DataOceanDemoAlt@123';
GRANT SELECT, SHOW VIEW ON dataocean_acceptance_alt.* TO 'dataocean_demo_alt'@'%';
USE dataocean_acceptance_alt;
CREATE TABLE IF NOT EXISTS context_marker (
    id BIGINT PRIMARY KEY,
    marker VARCHAR(50) NOT NULL,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文隔离验收标识';
INSERT IGNORE INTO context_marker (id, marker, created_at)
VALUES (1, 'ALT_DATASOURCE', '2026-09-13 13:00:00');

CREATE DATABASE IF NOT EXISTS dataocean_acceptance_ui
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dataocean_demo_ui'@'%' IDENTIFIED BY 'DataOceanDemoUi@123';
ALTER USER 'dataocean_demo_ui'@'%' IDENTIFIED BY 'DataOceanDemoUi@123';
GRANT SELECT, SHOW VIEW ON dataocean_acceptance_ui.* TO 'dataocean_demo_ui'@'%';
USE dataocean_acceptance_ui;
CREATE TABLE IF NOT EXISTS ui_marker (
    id BIGINT PRIMARY KEY,
    marker VARCHAR(50) NOT NULL,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='浏览器创建数据源验收标识';
INSERT IGNORE INTO ui_marker (id, marker, created_at)
VALUES (1, 'UI_DATASOURCE', '2026-09-13 13:00:00');
