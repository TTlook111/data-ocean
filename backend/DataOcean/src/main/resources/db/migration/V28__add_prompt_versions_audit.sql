ALTER TABLE query_task
    ADD COLUMN prompt_versions JSON NULL COMMENT 'Agent 调用使用的 Prompt 模板版本列表' AFTER masked_fields;

ALTER TABLE query_audit_log
    ADD COLUMN prompt_versions JSON NULL COMMENT 'Agent 调用使用的 Prompt 模板版本列表' AFTER used_fields;

INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('prompt:manage', 'Prompt 管理', 'prompt', '维护 Prompt 模板、版本与效果分析')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code = 'prompt:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
