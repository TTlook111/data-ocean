-- 添加元数据管理权限并分配给 DATA_MANAGER 和 ADMIN 角色
INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('metadata:manage', '元数据管理', 'metadata', '采集、查看和管理元数据')
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), module = VALUES(module), description = VALUES(description);

-- DATA_MANAGER (role_id=3) 分配 metadata:manage
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE permission_code = 'metadata:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ADMIN (role_id=5) 分配 metadata:manage
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE permission_code = 'metadata:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- DATA_MANAGER 也需要 datasource:manage 权限（元数据采集依赖数据源访问）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE permission_code = 'datasource:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
