-- 人员、部门、角色及公共可靠提交的物理设计；尚未在真实 MySQL 执行。
-- 仅面向新平台库，不含 USE、建库、删库、旧库升级或业务源 SQL。
-- 要求实际 MySQL 8 版本支持强制 CHECK（8.0.16 起）；连接会话时区固定 UTC。

CREATE TABLE iam_security_guard (
    id TINYINT UNSIGNED NOT NULL,
    security_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    bootstrap_completed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_security_guard_singleton CHECK (id = 1),
    CONSTRAINT ck_security_bootstrap CHECK (bootstrap_completed IN (0, 1)),
    CONSTRAINT ck_security_version CHECK (security_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(254) NULL,
    management_note VARCHAR(300) NULL,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    authentication_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    first_login_at DATETIME(3) NULL,
    disabled_at DATETIME(3) NULL,
    archived_at DATETIME(3) NULL,
    archived_affiliation JSON NULL,
    status_reason VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY ix_user_lifecycle_updated (lifecycle, updated_at, id),
    CONSTRAINT ck_user_lifecycle CHECK (lifecycle IN ('ENABLED', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_user_version CHECK (version > 0 AND authentication_version > 0),
    CONSTRAINT ck_user_must_change CHECK (must_change_password IN (0, 1)),
    CONSTRAINT ck_user_archive CHECK (lifecycle <> 'ARCHIVED' OR (archived_at IS NOT NULL AND archived_affiliation IS NOT NULL)),
    CONSTRAINT ck_user_disabled CHECK (lifecycle <> 'DISABLED' OR disabled_at IS NOT NULL),
    CONSTRAINT fk_user_creator FOREIGN KEY (created_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_user_credential (
    user_id BIGINT UNSIGNED NOT NULL,
    password_hash VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_revision INT UNSIGNED NOT NULL DEFAULT 1,
    changed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT ck_credential_revision CHECK (credential_revision > 0),
    CONSTRAINT fk_credential_user FOREIGN KEY (user_id) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_department (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    parent_id BIGINT UNSIGNED NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    manager_user_id BIGINT UNSIGNED NULL,
    note VARCHAR(300) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    access_review_required BOOLEAN NOT NULL DEFAULT FALSE,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    disabled_at DATETIME(3) NULL,
    archived_at DATETIME(3) NULL,
    status_reason VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_code (code),
    KEY ix_department_parent (parent_id, lifecycle, sort_order, id),
    CONSTRAINT ck_department_lifecycle CHECK (lifecycle IN ('ENABLED', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_department_version CHECK (version > 0),
    CONSTRAINT ck_department_access_review CHECK (access_review_required IN (0, 1)),
    CONSTRAINT ck_department_archive CHECK (lifecycle <> 'ARCHIVED' OR archived_at IS NOT NULL),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES iam_department (id),
    CONSTRAINT fk_department_manager FOREIGN KEY (manager_user_id) REFERENCES iam_user (id),
    CONSTRAINT fk_department_creator FOREIGN KEY (created_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    name_key VARCHAR(160) NOT NULL,
    description VARCHAR(300) NULL,
    is_builtin BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    version INT UNSIGNED NOT NULL DEFAULT 1,
    disabled_at DATETIME(3) NULL,
    archived_at DATETIME(3) NULL,
    status_reason VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code),
    UNIQUE KEY uk_role_name_key (name_key),
    KEY ix_role_lifecycle_updated (lifecycle, updated_at, id),
    CONSTRAINT ck_role_lifecycle CHECK (lifecycle IN ('ENABLED', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_role_version CHECK (version > 0),
    CONSTRAINT ck_role_builtin CHECK (is_builtin IN (0, 1)),
    CONSTRAINT ck_role_archive CHECK (lifecycle <> 'ARCHIVED' OR archived_at IS NOT NULL),
    CONSTRAINT fk_role_creator FOREIGN KEY (created_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_permission (
    code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page_permission_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    module_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    permission_kind VARCHAR(12) NOT NULL,
    is_protected BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (code),
    KEY ix_permission_page (page_permission_code, sort_order),
    CONSTRAINT ck_permission_kind CHECK (permission_kind IN ('PAGE', 'ACTION')),
    CONSTRAINT ck_permission_flags CHECK (is_protected IN (0, 1) AND enabled IN (0, 1)),
    CONSTRAINT ck_permission_page CHECK (
        (permission_kind = 'PAGE' AND page_permission_code IS NULL)
        OR (permission_kind = 'ACTION' AND page_permission_code IS NOT NULL)
    ),
    CONSTRAINT fk_permission_page FOREIGN KEY (page_permission_code) REFERENCES iam_permission (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 当前角色关系仍规范化保存；不可变快照仅供历史核对及受保护恢复，不参与当前放行。
CREATE TABLE iam_role_snapshot (
    role_id BIGINT UNSIGNED NOT NULL,
    role_version INT UNSIGNED NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300) NULL,
    lifecycle VARCHAR(16) NOT NULL,
    permission_codes JSON NOT NULL,
    grantable_permission_codes JSON NOT NULL,
    changed_by BIGINT UNSIGNED NULL,
    changed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (role_id, role_version),
    CONSTRAINT ck_role_snapshot_version CHECK (role_version > 0),
    CONSTRAINT ck_role_snapshot_lifecycle CHECK (lifecycle IN ('ENABLED', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT fk_role_snapshot_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_role_snapshot_actor FOREIGN KEY (changed_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_user_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    assigned_role_version INT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NOT NULL,
    assigned_at DATETIME(3) NOT NULL,
    ended_by BIGINT UNSIGNED NULL,
    ended_at DATETIME(3) NULL,
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role_active (user_id, role_id, active_marker),
    KEY ix_role_members (role_id, active_marker, user_id),
    CONSTRAINT ck_user_role_time CHECK (ended_at IS NULL OR ended_at >= assigned_at),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES iam_user (id),
    CONSTRAINT fk_user_role_snapshot FOREIGN KEY (role_id, assigned_role_version) REFERENCES iam_role_snapshot (role_id, role_version),
    CONSTRAINT fk_user_role_actor FOREIGN KEY (assigned_by) REFERENCES iam_user (id),
    CONSTRAINT fk_user_role_ender FOREIGN KEY (ended_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_user_department (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    joined_by BIGINT UNSIGNED NOT NULL,
    joined_at DATETIME(3) NOT NULL,
    left_by BIGINT UNSIGNED NULL,
    left_at DATETIME(3) NULL,
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN left_at IS NULL THEN 1 ELSE NULL END) STORED,
    primary_marker TINYINT GENERATED ALWAYS AS (CASE WHEN left_at IS NULL AND is_primary = TRUE THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_department_active (user_id, department_id, active_marker),
    UNIQUE KEY uk_user_primary_department (user_id, primary_marker),
    KEY ix_department_members (department_id, active_marker, user_id),
    CONSTRAINT ck_membership_time CHECK (left_at IS NULL OR left_at >= joined_at),
    CONSTRAINT ck_membership_primary CHECK (is_primary IN (0, 1)),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES iam_user (id),
    CONSTRAINT fk_membership_department FOREIGN KEY (department_id) REFERENCES iam_department (id),
    CONSTRAINT fk_membership_actor FOREIGN KEY (joined_by) REFERENCES iam_user (id),
    CONSTRAINT fk_membership_leaver FOREIGN KEY (left_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_role_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_by BIGINT UNSIGNED NULL,
    granted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    KEY ix_permission_roles (permission_code, role_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES iam_permission (code),
    CONSTRAINT fk_role_permission_actor FOREIGN KEY (granted_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE iam_role_grant_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configured_by BIGINT UNSIGNED NULL,
    configured_at DATETIME(3) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    KEY ix_grant_permission_roles (permission_code, role_id),
    CONSTRAINT fk_grant_limit_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_grant_limit_permission FOREIGN KEY (permission_code) REFERENCES iam_permission (code),
    CONSTRAINT fk_grant_limit_actor FOREIGN KEY (configured_by) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE audit_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_kind VARCHAR(16) NOT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    actor_snapshot JSON NOT NULL,
    action_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_name VARCHAR(200) NULL,
    outcome VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NULL,
    before_summary JSON NULL,
    after_summary JSON NULL,
    security_version BIGINT UNSIGNED NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    business_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY ix_audit_actor_time (actor_user_id, occurred_at, id),
    KEY ix_audit_target_time (target_type, target_id, occurred_at, id),
    KEY ix_audit_action_time (action_code, occurred_at, id),
    KEY ix_audit_time (occurred_at, id),
    KEY ix_audit_command (command_id),
    CONSTRAINT ck_audit_actor CHECK (actor_kind IN ('USER', 'SYSTEM', 'BOOTSTRAP')),
    CONSTRAINT ck_audit_user CHECK (actor_kind <> 'USER' OR actor_user_id IS NOT NULL),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'REJECTED', 'FAILED')),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_command (
    principal_kind VARCHAR(16) NOT NULL,
    principal_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    operation_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hmac BINARY(32) NOT NULL,
    hmac_key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    result_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_ref JSON NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (principal_kind, principal_id, operation_code, command_id),
    KEY ix_command_id (command_id, actor_user_id),
    KEY ix_command_state (state, created_at),
    CONSTRAINT ck_command_state CHECK (state IN ('ACCEPTED', 'COMMITTED', 'REJECTED')),
    CONSTRAINT ck_command_principal CHECK (principal_kind IN ('USER', 'SERVICE')),
    CONSTRAINT ck_command_user CHECK (principal_kind <> 'USER' OR actor_user_id IS NOT NULL),
    CONSTRAINT ck_command_completed CHECK (state = 'ACCEPTED' OR completed_at IS NOT NULL),
    CONSTRAINT fk_command_actor FOREIGN KEY (actor_user_id) REFERENCES iam_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    aggregate_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_version INT UNSIGNED NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload JSON NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    leased_until DATETIME(3) NULL,
    occurred_at DATETIME(3) NOT NULL,
    delivered_at DATETIME(3) NULL,
    last_error_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (event_id),
    KEY ix_outbox_dispatch (delivery_state, available_at, leased_until),
    KEY ix_outbox_aggregate (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT ck_outbox_schema CHECK (schema_version > 0 AND aggregate_version > 0),
    CONSTRAINT ck_outbox_state CHECK (delivery_state IN ('PENDING', 'CLAIMED', 'DELIVERED')),
    CONSTRAINT ck_outbox_lease CHECK (delivery_state <> 'CLAIMED' OR (lease_token IS NOT NULL AND leased_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_event_receipt (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_code),
    CONSTRAINT fk_receipt_event FOREIGN KEY (event_id) REFERENCES sys_outbox (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- guard 的单行初始化属于新库基线；首个账号和密码另走受控初始化，不在 SQL 保存默认凭据。
INSERT INTO iam_security_guard (id, security_version, bootstrap_completed, updated_at)
VALUES (1, 1, FALSE, UTC_TIMESTAMP(3));
