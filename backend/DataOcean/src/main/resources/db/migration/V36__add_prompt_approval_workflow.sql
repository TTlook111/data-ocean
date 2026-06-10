-- V36: 为 Prompt 模板添加审批流程

-- 1. 添加 status 字段
ALTER TABLE prompt_template
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' COMMENT '模板状态（DRAFT/PENDING_REVIEW/APPROVED/REJECTED）';

-- 2. 版本表增加状态：prompt_template 表保留线上发布版本，草稿/待审版本在版本表中流转
ALTER TABLE prompt_template_version
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' COMMENT '版本状态（DRAFT/PENDING_REVIEW/APPROVED/REJECTED）' AFTER is_active;

-- 3. 更新现有数据：所有已启用的模板设为 APPROVED
UPDATE prompt_template SET status = 'APPROVED' WHERE enabled = 1;
UPDATE prompt_template_version SET status = 'APPROVED' WHERE is_active = 1;

-- 4. 添加审批权限
INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('prompt:approve', 'Prompt 审批', 'prompt', '审核 Prompt 模板修改')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

-- 5. 将审批权限授予 ADMIN 角色
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code = 'prompt:approve'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 6. 添加索引
CREATE INDEX idx_prompt_template_status ON prompt_template(status);
CREATE INDEX idx_prompt_version_status ON prompt_template_version(template_id, status, version_no);
