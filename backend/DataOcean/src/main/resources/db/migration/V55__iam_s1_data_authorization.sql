-- IAM-SIMPLE-1 B2：独立数据授权、结构化记录条件和字段保护事实。
-- 只建立 S1 新表，不读取、迁移、回填或删除任何旧权限事实。

CREATE TABLE IF NOT EXISTS iam_s1_data_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    protocol_version VARCHAR(50) NOT NULL DEFAULT 'IAM-SIMPLE-1',
    subject_type VARCHAR(20) NOT NULL,
    subject_id BIGINT NOT NULL,
    department_scope VARCHAR(30) NULL,
    datasource_id BIGINT NOT NULL,
    resource_scope VARCHAR(20) NOT NULL,
    metadata_snapshot_id BIGINT NULL,
    table_name VARCHAR(200) NULL,
    effect VARCHAR(10) NOT NULL,
    grant_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    source_reference_id BIGINT NULL,
    valid_from DATETIME NOT NULL,
    valid_until DATETIME NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revision_no BIGINT NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_iam_s1_grant_subject (protocol_version, subject_type, subject_id, status),
    INDEX idx_iam_s1_grant_datasource (protocol_version, datasource_id, status, effect),
    INDEX idx_iam_s1_grant_resource (datasource_id, resource_scope, table_name, metadata_snapshot_id),
    INDEX idx_iam_s1_grant_validity (status, valid_from, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1数据授权';

CREATE TABLE IF NOT EXISTS iam_s1_data_grant_column (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    grant_id BIGINT NOT NULL,
    metadata_snapshot_id BIGINT NOT NULL,
    column_meta_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    column_name VARCHAR(200) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_grant_column (grant_id, column_meta_id),
    INDEX idx_iam_s1_grant_column_grant (grant_id),
    INDEX idx_iam_s1_grant_column_resource (metadata_snapshot_id, table_name, column_meta_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1授权明确字段';

CREATE TABLE IF NOT EXISTS iam_s1_row_condition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    grant_id BIGINT NOT NULL,
    metadata_snapshot_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    match_type VARCHAR(10) NOT NULL,
    sequence_no INT NOT NULL,
    column_meta_id BIGINT NOT NULL,
    column_name VARCHAR(200) NOT NULL,
    operator_code VARCHAR(20) NOT NULL,
    value_type VARCHAR(30) NOT NULL,
    structured_value_json TEXT NULL,
    parameter_reference VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_row_condition_order (grant_id, sequence_no),
    INDEX idx_iam_s1_row_condition_grant (grant_id),
    INDEX idx_iam_s1_row_condition_resource (metadata_snapshot_id, table_name, column_meta_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1结构化记录条件';

CREATE TABLE IF NOT EXISTS iam_s1_field_protection (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    protocol_version VARCHAR(50) NOT NULL DEFAULT 'IAM-SIMPLE-1',
    datasource_id BIGINT NOT NULL,
    metadata_snapshot_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    column_meta_id BIGINT NOT NULL,
    column_name VARCHAR(200) NOT NULL,
    protection_level VARCHAR(20) NOT NULL,
    mask_policy VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revision_no BIGINT NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_iam_s1_protection_resource (protocol_version, datasource_id, metadata_snapshot_id, table_name, column_meta_id, status),
    INDEX idx_iam_s1_protection_column (column_meta_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1字段保护';
