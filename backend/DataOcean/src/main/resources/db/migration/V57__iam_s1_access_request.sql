-- IAM-SIMPLE-1 B4：新体系访问申请与审批。
-- 只新增 S1 前向表；不读取、迁移、回填或删除任何旧审批与旧授权事实。
-- 申请与审批只保存资源标识、批准范围和关联的 S1 授权 ID，不保存业务原值与权限参数原值。

CREATE TABLE IF NOT EXISTS iam_s1_access_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    protocol_version VARCHAR(50) NOT NULL DEFAULT 'IAM-SIMPLE-1',
    requester_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    metadata_snapshot_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    requested_columns_json TEXT NOT NULL,
    row_scope VARCHAR(20) NOT NULL DEFAULT 'ALL',
    requested_valid_until DATETIME NULL,
    purpose VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    revision_no BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_iam_s1_access_request_requester (protocol_version, requester_id, status),
    INDEX idx_iam_s1_access_request_datasource (protocol_version, datasource_id, status),
    INDEX idx_iam_s1_access_request_resource (metadata_snapshot_id, table_name, status),
    INDEX idx_iam_s1_access_request_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1访问申请';

CREATE TABLE IF NOT EXISTS iam_s1_access_approval (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    decision VARCHAR(20) NOT NULL,
    approved_columns_json TEXT NULL,
    approved_valid_from DATETIME NULL,
    approved_valid_until DATETIME NULL,
    generated_grant_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iam_s1_access_approval_request (request_id),
    INDEX idx_iam_s1_access_approval_reviewer (reviewer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM-SIMPLE-1访问审批';
