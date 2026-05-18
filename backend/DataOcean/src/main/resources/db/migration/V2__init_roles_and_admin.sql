INSERT INTO sys_department (id, parent_id, dept_name, dept_code, sort_order, status)
VALUES (1, NULL, '总部', 'HQ', 0, 1)
ON DUPLICATE KEY UPDATE dept_name = VALUES(dept_name), status = VALUES(status);

INSERT INTO sys_role (id, role_code, role_name, description, status)
VALUES
    (1, 'USER', '普通员工', '默认业务用户', 1),
    (2, 'ANALYST', '数据分析师', '可发起数据查询与分析', 1),
    (3, 'DATA_MANAGER', '数据管理员', '管理数据源和元数据', 1),
    (4, 'SECURITY_MANAGER', '安全管理员', '管理权限与安全策略', 1),
    (5, 'ADMIN', '超级管理员', '系统最高权限管理员', 1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), description = VALUES(description), status = VALUES(status);

INSERT INTO sys_permission (id, permission_code, permission_name, module, description)
VALUES
    (1, '*', '全部权限', 'system', '超级管理员拥有的全部权限'),
    (2, 'user:manage', '用户管理', 'user', '创建、更新、删除和查询用户'),
    (3, 'role:view', '角色查看', 'user', '查看角色列表'),
    (4, 'department:manage', '部门管理', 'user', '查看、创建和删除部门')
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), module = VALUES(module), description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO sys_user (id, username, password_hash, real_name, email, department_id, status, deleted)
VALUES
    (1, 'admin', '$2b$10$Z4VL2owmJAjp02aND9yituM3FCnD1pQFbxTC8.MjkTV6XJYm9hS8K', '超级管理员', 'admin@dataocean.local', 1, 1, 0)
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name), email = VALUES(email), status = VALUES(status), deleted = VALUES(deleted);

INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 5)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
