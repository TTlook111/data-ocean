INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES
    ('system:ai-config:view', 'AI配置查看', 'system', '查看AI服务配置和供应商配置'),
    ('system:ai-config:manage', 'AI配置管理', 'system', '维护AI服务配置、供应商、模型和向量化配置')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code IN ('system:ai-config:view', 'system:ai-config:manage')
WHERE r.role_code = 'ADMIN'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
