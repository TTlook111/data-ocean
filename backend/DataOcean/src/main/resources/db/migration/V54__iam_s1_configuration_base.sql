-- IAM-SIMPLE-1 B1：独立权限配置基础。
-- 本迁移只建立 S1 表和固定功能目录，不读取、迁移或删除任何旧权限事实。

CREATE TABLE IF NOT EXISTS iam_s1_function (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    function_code VARCHAR(100) NOT NULL,
    function_name VARCHAR(100) NOT NULL,
    function_description VARCHAR(500) NOT NULL,
    business_domain VARCHAR(50) NOT NULL,
    workspace VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    dependency_codes VARCHAR(500) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_function_code (function_code),
    INDEX idx_iam_s1_function_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1固定功能目录';

CREATE TABLE IF NOT EXISTS iam_s1_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(100) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    protected_role TINYINT NOT NULL DEFAULT 0,
    built_in TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_role_code (role_code),
    INDEX idx_iam_s1_role_status (status),
    INDEX idx_iam_s1_role_protected (protected_role, built_in)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1角色';

CREATE TABLE IF NOT EXISTS iam_s1_role_function (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    function_id BIGINT NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_role_function (role_id, function_id),
    INDEX idx_iam_s1_role_function_role (role_id),
    INDEX idx_iam_s1_role_function_function (function_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1角色功能关系';

CREATE TABLE IF NOT EXISTS iam_s1_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    revision_no BIGINT NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_user_role (user_id, role_id),
    INDEX idx_iam_s1_user_role_user (user_id, status),
    INDEX idx_iam_s1_user_role_role (role_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1用户角色绑定';

CREATE TABLE IF NOT EXISTS iam_s1_role_datasource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_role_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    revision_no BIGINT NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_role_datasource (user_role_id, datasource_id),
    INDEX idx_iam_s1_role_datasource_binding (user_role_id, status),
    INDEX idx_iam_s1_role_datasource_datasource (datasource_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1用户角色后台负责源';

CREATE TABLE IF NOT EXISTS iam_s1_permission_revision (
    revision_no BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NULL,
    change_type VARCHAR(50) NOT NULL,
    operator_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_iam_s1_revision_target (target_type, target_id),
    INDEX idx_iam_s1_revision_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1权限修订';

CREATE TABLE IF NOT EXISTS iam_s1_audit_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(50) NOT NULL,
    operator_id BIGINT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NULL,
    before_summary VARCHAR(2000) NULL,
    after_summary VARCHAR(2000) NULL,
    reason VARCHAR(500) NULL,
    execution_id VARCHAR(100) NULL,
    success TINYINT NOT NULL DEFAULT 1,
    failure_reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_iam_s1_audit_target (target_type, target_id),
    INDEX idx_iam_s1_audit_operator (operator_id, created_at),
    INDEX idx_iam_s1_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1权限审计';

CREATE TABLE IF NOT EXISTS iam_s1_bootstrap_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    protocol_version VARCHAR(50) NOT NULL,
    state VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    target_user_id BIGINT NULL,
    completed_at DATETIME NULL,
    implementation_version VARCHAR(100) NULL,
    execution_id VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_bootstrap_protocol (protocol_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1首次管理员初始化状态';

INSERT INTO iam_s1_function
    (id, function_code, function_name, function_description, business_domain, workspace, status, dependency_codes)
VALUES
    (1, 'query:use', '使用问数', '提交自然语言问题并查看允许范围内的查询结果。', '问数', '问数工具', 'ACTIVE', ''),
    (2, 'query:sql:view', '查看 SQL', '查看当前问数任务生成的 SQL，不扩大数据范围。', '问数', '问数工具', 'ACTIVE', 'query:use'),
    (3, 'query:export', '导出结果', '导出当前已获准且已再次检查保护规则的查询结果。', '问数', '问数工具', 'ACTIVE', 'query:use'),
    (4, 'admin:workbench:view', '查看工作台', '查看后台工作台摘要和待处理事项。', '工作台', '工作台', 'ACTIVE', ''),
    (5, 'datasource:view', '查看数据源', '查看有后台负责范围的数据源基本信息和状态。', '数据接入', '数据源', 'ACTIVE', ''),
    (6, 'datasource:manage', '维护数据源', '创建、编辑、启停和维护数据源配置。', '数据接入', '数据源', 'ACTIVE', 'datasource:view'),
    (7, 'metadata:collect:view', '查看采集任务', '查看元数据采集任务和执行状态。', '数据资产', '采集任务', 'ACTIVE', ''),
    (8, 'metadata:collect:run', '执行采集', '发起允许的数据源元数据采集。', '数据资产', '采集任务', 'ACTIVE', 'metadata:collect:view'),
    (9, 'metadata:view', '查看资产结构', '查看已治理元数据的表、字段和资产结构。', '数据资产', '资产目录', 'ACTIVE', ''),
    (10, 'metadata:release:view', '查看元数据版本', '查看元数据快照版本和发布状态。', '数据治理', '版本发布', 'ACTIVE', ''),
    (11, 'metadata:release:review', '审核快照', '审核元数据快照是否可以进入发布流程。', '数据治理', '版本发布', 'ACTIVE', 'metadata:release:view'),
    (12, 'metadata:release:publish', '发布/回滚快照', '发布或回滚已审核的元数据快照。', '数据治理', '版本发布', 'ACTIVE', 'metadata:release:view'),
    (13, 'governance:view', '查看治理总览', '查看治理质量、状态和风险总览。', '数据治理', '治理总览', 'ACTIVE', ''),
    (14, 'governance:check', '执行质量检查', '对元数据快照执行质量检查。', '数据治理', '治理总览', 'ACTIVE', 'governance:view'),
    (15, 'governance:issue:view', '查看质量问题', '查看元数据质量问题及其处理状态。', '数据治理', '问题中心', 'ACTIVE', ''),
    (16, 'governance:issue:manage', '处理质量问题', '分配、处理和批量更新质量问题。', '数据治理', '问题中心', 'ACTIVE', 'governance:issue:view'),
    (17, 'governance:rule:view', '查看治理规则', '查看数据质量规则和启用状态。', '数据治理', '规则与状态', 'ACTIVE', ''),
    (18, 'governance:rule:manage', '维护治理规则', '维护治理规则及其启用状态。', '数据治理', '规则与状态', 'ACTIVE', 'governance:rule:view'),
    (19, 'governance:field:view', '查看字段治理', '查看字段治理状态和可信度信息。', '数据治理', '字段治理', 'ACTIVE', ''),
    (20, 'governance:field:manage', '维护字段治理/审核反馈', '维护字段治理状态并处理字段反馈。', '数据治理', '字段治理', 'ACTIVE', 'governance:field:view'),
    (21, 'glossary:view', '查看业务术语', '查看已登记的业务术语及其状态。', '语义中心', '业务术语', 'ACTIVE', ''),
    (22, 'glossary:manage', '维护业务术语', '新建、修改和关联业务术语。', '语义中心', '业务术语', 'ACTIVE', 'glossary:view'),
    (23, 'glossary:approve', '审核术语', '审核业务术语及其发布前状态。', '语义中心', '业务术语', 'ACTIVE', 'glossary:view'),
    (24, 'knowledge:view', '查看知识文档', '查看语义知识文档、版本和索引状态。', '语义中心', '语义知识', 'ACTIVE', ''),
    (25, 'knowledge:manage', '维护知识文档', '创建、编辑和提交知识文档。', '语义中心', '语义知识', 'ACTIVE', 'knowledge:view'),
    (26, 'knowledge:approve', '审核知识文档', '审核知识文档版本。', '语义中心', '语义知识', 'ACTIVE', 'knowledge:view'),
    (27, 'knowledge:publish', '发布/回滚知识', '发布或回滚已审核知识版本并管理索引状态。', '语义中心', '语义知识', 'ACTIVE', 'knowledge:view'),
    (28, 'prompt:view', '查看 AI 提示词', '查看提示词策略摘要和版本状态。', '语义中心', 'Prompt 策略', 'ACTIVE', ''),
    (29, 'prompt:manage', '维护 AI 提示词', '维护提示词内容并提交审核。', '语义中心', 'Prompt 策略', 'ACTIVE', 'prompt:view'),
    (30, 'prompt:approve', '审核 AI 提示词', '审核提示词版本。', '语义中心', 'Prompt 策略', 'ACTIVE', 'prompt:view'),
    (31, 'security:permission:view', '查看授权配置', '查看 S1 角色、负责源和授权配置摘要。', '权限与组织', '授权配置', 'ACTIVE', ''),
    (32, 'security:permission:manage', '维护授权配置', '维护允许管理范围内的授权配置。', '权限与组织', '授权配置', 'ACTIVE', 'security:permission:view'),
    (33, 'security:mask:view', '查看字段保护', '查看字段保护状态和策略摘要。', '权限与组织', '字段保护', 'ACTIVE', ''),
    (34, 'security:mask:manage', '维护字段保护', '维护字段隐藏、保护和值展示策略。', '权限与组织', '字段保护', 'ACTIVE', 'security:mask:view'),
    (35, 'security:effective:view', '查看用户实际权限', '查看用户当前实际生效的 S1 能力和范围摘要。', '权限与组织', '实际权限', 'ACTIVE', ''),
    (36, 'security:approval:view', '查看访问申请', '查看本人或负责范围内的访问申请。', '权限与组织', '访问审批', 'ACTIVE', ''),
    (37, 'security:approval:review', '审批数据访问', '审批负责范围内的数据访问申请。', '权限与组织', '访问审批', 'ACTIVE', 'security:approval:view'),
    (38, 'organization:user:view', '查看用户', '查看用户基本资料、部门和角色摘要。', '权限与组织', '用户', 'ACTIVE', ''),
    (39, 'organization:user:manage', '维护用户', '维护普通账号资料、启停、部门和业务角色。', '权限与组织', '用户', 'ACTIVE', 'organization:user:view'),
    (40, 'organization:user:export', '导出用户列表', '导出允许查看的用户基础信息。', '权限与组织', '用户', 'ACTIVE', 'organization:user:view'),
    (41, 'organization:role:view', '查看角色', '查看角色用途、功能组合和成员。', '权限与组织', '角色', 'ACTIVE', ''),
    (42, 'organization:role:manage', '维护角色', '新建或修改非系统管理员角色及其固定功能组合。', '权限与组织', '角色', 'ACTIVE', 'organization:role:view'),
    (43, 'organization:department:view', '查看部门', '查看真实组织部门树和状态。', '权限与组织', '部门', 'ACTIVE', ''),
    (44, 'organization:department:manage', '维护部门', '维护符合组织规则的部门结构。', '权限与组织', '部门', 'ACTIVE', 'organization:department:view'),
    (45, 'organization:permission:view', '查看功能说明', '查看固定 54 项功能的中文作用和边界。', '权限与组织', '功能目录', 'ACTIVE', ''),
    (46, 'audit:view', '查看查询审计', '查看负责范围内的查询执行记录、状态和统计。', '运营与平台', '查询分析', 'ACTIVE', ''),
    (47, 'audit:export', '导出审计', '导出允许查看的安全审计信息，不含业务敏感原值。', '运营与平台', '查询分析', 'ACTIVE', 'audit:view'),
    (48, 'lineage:view', '查看数据血缘', '查看表字段来源关系和影响分析。', '运营与平台', '数据血缘', 'ACTIVE', 'metadata:view'),
    (49, 'lineage:manage', '维护血缘关系', '维护允许范围内的数据血缘关系。', '运营与平台', '数据血缘', 'ACTIVE', 'lineage:view'),
    (50, 'system:runtime:view', '查看运行状态', '查看服务、连接池和告警配置状态。', '运营与平台', '运行监控', 'ACTIVE', ''),
    (51, 'system:runtime:manage', '维护运行监控', '维护告警规则或重置连接池并说明影响。', '运营与平台', '运行监控', 'ACTIVE', 'system:runtime:view'),
    (52, 'operation-log:view', '查看操作日志', '查看管理员操作日志，只读不可修改。', '运营与平台', '操作日志', 'ACTIVE', ''),
    (53, 'system:ai-config:view', '查看 AI 配置', '查看模型和供应商摘要，不显示密钥原值。', '运营与平台', 'AI 配置', 'ACTIVE', ''),
    (54, 'system:ai-config:manage', '维护 AI 配置', '维护供应商、模型和运行参数，不显示密钥原值。', '运营与平台', 'AI 配置', 'ACTIVE', 'system:ai-config:view')
ON DUPLICATE KEY UPDATE
    function_name = VALUES(function_name),
    function_description = VALUES(function_description),
    business_domain = VALUES(business_domain),
    workspace = VALUES(workspace),
    status = VALUES(status),
    dependency_codes = VALUES(dependency_codes);

INSERT INTO iam_s1_role
    (id, role_code, role_name, description, status, protected_role, built_in)
VALUES
    (1, 'IAM_S1_SYSTEM_ADMIN', '系统管理员', 'IAM-SIMPLE-1唯一受保护内置角色，拥有全部后台能力和负责源范围，不自动拥有业务数据授权。', 1, 1, 1)
ON DUPLICATE KEY UPDATE
    role_name = VALUES(role_name),
    description = VALUES(description),
    status = VALUES(status),
    protected_role = VALUES(protected_role),
    built_in = VALUES(built_in);

INSERT INTO iam_s1_role_function (role_id, function_id)
SELECT 1, id FROM iam_s1_function
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id), function_id = VALUES(function_id);

INSERT INTO iam_s1_bootstrap_state (id, protocol_version, state)
VALUES (1, 'IAM-SIMPLE-1', 'PENDING')
ON DUPLICATE KEY UPDATE protocol_version = VALUES(protocol_version);
