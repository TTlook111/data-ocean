-- Register field tag governance permissions for module 010.
INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES
    ('field-tag:manage', '字段治理管理', 'field', '管理字段标签、可信度和反馈审核'),
    ('feedback:review', '反馈审核', 'field', '审核用户对查询字段的负向反馈')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role_id, permission_id
FROM (
    SELECT 3 AS role_id, id AS permission_id
    FROM sys_permission
    WHERE permission_code IN ('field-tag:manage', 'feedback:review')
    UNION ALL
    SELECT 5 AS role_id, id AS permission_id
    FROM sys_permission
    WHERE permission_code IN ('field-tag:manage', 'feedback:review')
) role_permissions
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
