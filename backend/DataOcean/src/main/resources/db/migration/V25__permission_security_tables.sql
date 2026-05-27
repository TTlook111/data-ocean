-- =====================================================
-- V25: 权限与安全模块 - 扩展数据源访问控制 + 行列级策略
-- 注意：RENAME COLUMN 语法要求 MySQL 8.0+
-- =====================================================

-- 1. 扩展 datasource_access 表：支持多维度授权主体
ALTER TABLE datasource_access
    ADD COLUMN subject_type VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '授权主体类型: USER/ROLE/DEPARTMENT' AFTER datasource_id,
    ADD COLUMN can_query TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许查询',
    ADD COLUMN can_export TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许导出',
    ADD COLUMN can_view_sql TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许查看生成的SQL';

-- 将 user_id 重命名为 subject_id（MySQL 8 支持 RENAME COLUMN）
ALTER TABLE datasource_access RENAME COLUMN user_id TO subject_id;

-- 删除旧唯一索引，创建新的复合唯一索引
ALTER TABLE datasource_access DROP INDEX uk_datasource_access_user;
ALTER TABLE datasource_access ADD UNIQUE INDEX uk_datasource_subject (datasource_id, subject_type, subject_id);

-- 2. 创建行列级访问策略表
CREATE TABLE IF NOT EXISTS datasource_access_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '策略ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    subject_type VARCHAR(20) NOT NULL COMMENT '授权主体类型: USER/ROLE/DEPARTMENT',
    subject_id BIGINT NOT NULL COMMENT '授权主体ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名（* 表示所有表）',
    column_name VARCHAR(100) NULL COMMENT '列名（NULL 表示表级策略）',
    access_type VARCHAR(20) NOT NULL DEFAULT 'ALLOW' COMMENT '访问类型: ALLOW/DENY/MASK',
    mask_strategy VARCHAR(20) NULL COMMENT '脱敏策略: PHONE/ID_CARD/EMAIL/BANK_CARD/NAME',
    row_filter_expression VARCHAR(500) NULL COMMENT '行级过滤SQL表达式',
    created_by BIGINT NULL COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_policy_datasource_subject (datasource_id, subject_type, subject_id, table_name),
    INDEX idx_policy_table (datasource_id, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源行列级访问策略';

-- 3. 添加 security:manage 权限点
INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('security:manage', '安全权限管理', 'permission', '管理数据源访问权限和行列级策略')
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), module = VALUES(module), description = VALUES(description);

-- 将 security:manage 分配给 SECURITY_MANAGER(4) 和 ADMIN(5)
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE permission_code = 'security:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE permission_code = 'security:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
