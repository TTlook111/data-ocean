INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('role:manage', '角色管理', 'user', '维护角色成员关系')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code = 'role:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
