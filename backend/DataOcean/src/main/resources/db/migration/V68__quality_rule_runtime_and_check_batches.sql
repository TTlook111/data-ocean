-- 质量规则实例显式绑定已注册检查模板，并支持稳定目标范围、配置版本和软删除。
ALTER TABLE metadata_quality_rule
    ADD COLUMN template_code VARCHAR(80) DEFAULT NULL COMMENT '已注册检查模板编码' AFTER rule_code,
    ADD COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'GLOBAL' COMMENT '生效范围：GLOBAL/DATASOURCE/TABLE/COLUMN' AFTER builtin,
    ADD COLUMN datasource_id BIGINT DEFAULT NULL COMMENT '数据源级及以下范围使用' AFTER scope_type,
    ADD COLUMN target_table_name VARCHAR(200) DEFAULT NULL COMMENT '稳定表名目标，不绑定快照元数据行ID' AFTER datasource_id,
    ADD COLUMN target_column_name VARCHAR(200) DEFAULT NULL COMMENT '稳定字段名目标' AFTER target_table_name,
    ADD COLUMN parameters_json JSON DEFAULT NULL COMMENT '模板参数，不接受SQL或脚本' AFTER target_column_name,
    ADD COLUMN config_version INT NOT NULL DEFAULT 1 COMMENT '规则配置版本' AFTER parameters_json,
    ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间' AFTER config_version,
    ADD COLUMN deleted_by BIGINT DEFAULT NULL COMMENT '删除人' AFTER deleted_at,
    ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT '创建人' AFTER deleted_by,
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最近修改人' AFTER created_by,
    ADD INDEX idx_quality_rule_scope (scope_type, datasource_id, template_code, enabled, deleted_at),
    ADD INDEX idx_quality_rule_target (datasource_id, target_table_name, target_column_name),
    ADD INDEX idx_quality_rule_conflict (template_code(80), scope_type, datasource_id,
        target_table_name(64), target_column_name(64), enabled, deleted_at);

-- 只映射已经有真实检查器的内置规则。其他旧编码保持 NULL，服务端会明确标为不支持。
UPDATE metadata_quality_rule
SET template_code = rule_code
WHERE rule_code IN (
    'COMP_TABLE_COMMENT_MISSING', 'COMP_COLUMN_COMMENT_MISSING', 'COMP_PRIMARY_KEY_MISSING',
    'ACCU_TYPE_NAME_MISMATCH', 'ACCU_ENUM_ANOMALY',
    'CONS_CROSS_TABLE_TYPE_MISMATCH', 'CONS_CROSS_TABLE_COMMENT_CONFLICT',
    'TIME_SNAPSHOT_EXPIRED', 'TIME_TABLE_NO_UPDATE',
    'TRACE_FK_MISSING', 'TRACE_ISOLATED_TABLE',
    'DATA_NULL_RATE_HIGH', 'DATA_UNIQUE_VIOLATION', 'DATA_FK_ORPHAN', 'DATA_STALE_TABLE'
);

-- 将旧的固定阈值转为同一执行器使用的结构化模板参数，保留既有判定语义。
UPDATE metadata_quality_rule
SET parameters_json = CASE template_code
    WHEN 'DATA_NULL_RATE_HIGH' THEN JSON_OBJECT('threshold', COALESCE(threshold, 0.3000))
    WHEN 'DATA_STALE_TABLE' THEN JSON_OBJECT('staleDays', COALESCE(threshold, 30))
    WHEN 'TIME_SNAPSHOT_EXPIRED' THEN JSON_OBJECT('maxAgeDays', COALESCE(threshold, 7))
    WHEN 'ACCU_ENUM_ANOMALY' THEN JSON_OBJECT('maxDistinctValues', COALESCE(threshold, 50))
    WHEN 'DATA_UNIQUE_VIOLATION' THEN JSON_OBJECT('primaryKeyOnly', TRUE)
    ELSE parameters_json
END
WHERE template_code IN (
    'DATA_NULL_RATE_HIGH', 'DATA_STALE_TABLE', 'TIME_SNAPSHOT_EXPIRED',
    'ACCU_ENUM_ANOMALY', 'DATA_UNIQUE_VIOLATION'
);

CREATE TABLE quality_check_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    requested_scope VARCHAR(24) NOT NULL COMMENT 'FULL/DIMENSION/TABLE',
    requested_dimensions_json JSON DEFAULT NULL,
    requested_table_names_json JSON DEFAULT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/PARTIAL/FAILED/LEGACY',
    complete TINYINT NOT NULL DEFAULT 0,
    total_score DECIMAL(5,2) DEFAULT NULL,
    rule_count INT NOT NULL DEFAULT 0,
    executed_rule_count INT NOT NULL DEFAULT 0,
    failed_rule_count INT NOT NULL DEFAULT 0,
    not_applicable_rule_count INT NOT NULL DEFAULT 0,
    error_summary TEXT DEFAULT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_quality_batch_snapshot (snapshot_id, started_at DESC),
    INDEX idx_quality_batch_datasource (datasource_id, started_at DESC),
    INDEX idx_quality_batch_status (status, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量校验执行批次';

CREATE TABLE quality_check_batch_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    rule_id BIGINT DEFAULT NULL,
    rule_code VARCHAR(80) DEFAULT NULL,
    template_code VARCHAR(80) DEFAULT NULL,
    rule_version INT DEFAULT NULL,
    rule_name VARCHAR(200) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    dimension VARCHAR(20) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    deduction_points DECIMAL(4,1) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    datasource_id BIGINT DEFAULT NULL,
    target_table_name VARCHAR(200) DEFAULT NULL,
    target_column_name VARCHAR(200) DEFAULT NULL,
    parameters_json JSON DEFAULT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_EXECUTED' COMMENT 'SUCCESS/FAILURE/NOT_APPLICABLE/NOT_EXECUTED',
    reason TEXT DEFAULT NULL,
    target_count INT NOT NULL DEFAULT 0,
    checked_target_count INT NOT NULL DEFAULT 0,
    issue_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_batch_rule (batch_id, rule_id),
    INDEX idx_batch_rule_template (batch_id, template_code, status),
    INDEX idx_batch_rule_rule (rule_id, rule_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校验批次规则版本与覆盖';

CREATE TABLE metadata_quality_rule_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    rule_code VARCHAR(50) NOT NULL,
    template_code VARCHAR(80) DEFAULT NULL,
    config_version INT NOT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'BASELINE/CREATE/COPY/UPDATE/ENABLE/DISABLE/DELETE/RESTORE',
    rule_name VARCHAR(200) NOT NULL,
    dimension VARCHAR(20) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    check_target VARCHAR(20) NOT NULL,
    check_type VARCHAR(32) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    datasource_id BIGINT DEFAULT NULL,
    target_table_name VARCHAR(200) DEFAULT NULL,
    target_column_name VARCHAR(200) DEFAULT NULL,
    parameters_json JSON DEFAULT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    deduction_points DECIMAL(4,1) NOT NULL,
    builtin TINYINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    changed_by BIGINT DEFAULT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_quality_rule_revision (rule_id, changed_at DESC),
    INDEX idx_quality_rule_revision_version (rule_id, config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量规则配置及生命周期版本历史';

-- 只记录迁移时可核对的当前定义为基线，不把迁移前未知的变更伪装成历史事件。
INSERT INTO metadata_quality_rule_revision (
    rule_id, rule_code, template_code, config_version, action, rule_name, dimension, severity,
    description, check_target, check_type, scope_type, datasource_id, target_table_name,
    target_column_name, parameters_json, enabled, deduction_points, builtin, deleted,
    changed_by, changed_at
)
SELECT id, rule_code, template_code, config_version, 'BASELINE', rule_name, dimension, severity,
       description, check_target, check_type, scope_type, datasource_id, target_table_name,
       target_column_name, parameters_json, enabled, deduction_points, builtin,
       CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END, updated_by, CURRENT_TIMESTAMP
FROM metadata_quality_rule;

ALTER TABLE metadata_quality_issue
    ADD COLUMN check_batch_id BIGINT DEFAULT NULL COMMENT '发现问题的质量校验批次' AFTER rule_id,
    ADD INDEX idx_issue_check_batch (check_batch_id, status);

ALTER TABLE quality_check_result
    ADD COLUMN check_batch_id BIGINT DEFAULT NULL COMMENT '所属质量校验批次' AFTER snapshot_id,
    ADD INDEX idx_qcr_batch (check_batch_id, dimension);
