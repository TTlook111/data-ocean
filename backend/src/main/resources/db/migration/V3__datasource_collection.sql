-- 数据源、配置及秘密引用。目录和配置写入分别维护版本。
CREATE TABLE ds_source (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    name_key VARBINARY(1280) NOT NULL,
    lifecycle VARCHAR(48) CHARACTER SET ascii NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    catalog_version INT UNSIGNED NOT NULL DEFAULT 1,
    current_connection_revision_id BIGINT UNSIGNED NULL,
    current_snapshot_id BIGINT UNSIGNED NULL,
    active_task_id CHAR(36) CHARACTER SET ascii NULL,
    pending_change_id CHAR(36) CHARACTER SET ascii NULL,
    schedule_paused BOOLEAN NOT NULL DEFAULT FALSE,
    first_success_at DATETIME(3) NULL,
    scope_changed_at DATETIME(3) NULL,
    stale_since DATETIME(3) NULL,
    stale_scope_key BINARY(32) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_source_name (name_key),
    KEY ix_ds_source_status (lifecycle, updated_at, id),
    CONSTRAINT ck_ds_source_lifecycle CHECK (lifecycle IN ('DRAFT','ENABLED','DISABLED','RETIRED')),
    CONSTRAINT ck_ds_source_version CHECK (version > 0 AND catalog_version > 0),
    CONSTRAINT ck_ds_source_stale CHECK ((stale_since IS NULL) = (stale_scope_key IS NULL)),
    CONSTRAINT ck_ds_source_paused CHECK (schedule_paused IN (0,1))
);

CREATE TABLE ds_credential_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    key_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    ciphertext BLOB NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_secret_reference (source_id,id),
    CONSTRAINT fk_ds_secret_source FOREIGN KEY (source_id) REFERENCES ds_source (id)
);

CREATE TABLE ds_connection_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    database_type VARCHAR(48) CHARACTER SET ascii NULL,
    host VARCHAR(253) NULL,
    port SMALLINT UNSIGNED NULL,
    database_name VARCHAR(160) NULL,
    account VARCHAR(160) NULL,
    credential_revision_id BIGINT UNSIGNED NULL,
    purpose VARCHAR(1000) NULL,
    technical_contact_id BIGINT UNSIGNED NULL,
    business_contact_id BIGINT UNSIGNED NULL,
    max_connections SMALLINT UNSIGNED NOT NULL,
    collection_concurrency SMALLINT UNSIGNED NOT NULL,
    retry_limit SMALLINT UNSIGNED NOT NULL,
    task_timeout_seconds SMALLINT UNSIGNED NOT NULL,
    query_timeout_seconds SMALLINT UNSIGNED NOT NULL,
    row_limit SMALLINT UNSIGNED NOT NULL,
    sync_hours SMALLINT UNSIGNED NOT NULL,
    window_start_minute SMALLINT UNSIGNED NOT NULL,
    window_end_minute SMALLINT UNSIGNED NOT NULL,
    scope_key BINARY(32) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_config_revision (source_id,revision),
    UNIQUE KEY uk_ds_config_reference (source_id,id),
    KEY ix_ds_revision_tech_contact (technical_contact_id),
    KEY ix_ds_revision_business_contact (business_contact_id),
    KEY ix_ds_revision_credential (credential_revision_id),
    CONSTRAINT fk_ds_revision_source FOREIGN KEY (source_id) REFERENCES ds_source (id),
    CONSTRAINT fk_ds_revision_credential FOREIGN KEY (source_id,credential_revision_id)
        REFERENCES ds_credential_revision (source_id,id),
    CONSTRAINT ck_ds_revision_config CHECK (
        revision > 0 AND database_type IN ('MYSQL','POSTGRESQL') AND port > 0),
    CONSTRAINT ck_ds_revision_limits CHECK (
        max_connections BETWEEN 1 AND 20 AND collection_concurrency BETWEEN 1 AND 8
        AND collection_concurrency <= max_connections AND retry_limit BETWEEN 0 AND 5
        AND task_timeout_seconds BETWEEN 30 AND 3600 AND query_timeout_seconds BETWEEN 1 AND 300
        AND row_limit BETWEEN 1 AND 10000 AND sync_hours BETWEEN 1 AND 720
        AND window_start_minute < 1440 AND window_end_minute < 1440
        AND window_start_minute <> window_end_minute)
);

CREATE TABLE ds_connection_schema (
    connection_revision_id BIGINT UNSIGNED NOT NULL,
    schema_name VARCHAR(160) NOT NULL,
    schema_key VARBINARY(640) NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    PRIMARY KEY (connection_revision_id,schema_key),
    UNIQUE KEY uk_ds_revision_schema_order (connection_revision_id,ordinal),
    CONSTRAINT fk_ds_schema_revision FOREIGN KEY (connection_revision_id) REFERENCES ds_connection_revision (id)
);

CREATE TABLE ds_capability_check (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT UNSIGNED NOT NULL,
    source_id BIGINT UNSIGNED NULL,
    base_version INT UNSIGNED NULL,
    connection_fingerprint BINARY(32) NOT NULL,
    credential_fingerprint BINARY(32) NOT NULL,
    fingerprint_key_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    adapter_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    policy_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    detected_version VARCHAR(64) NULL,
    verified_scope JSON NOT NULL,
    coverage JSON NOT NULL,
    results JSON NOT NULL,
    read_only_safety VARCHAR(48) CHARACTER SET ascii NOT NULL,
    business_read_status VARCHAR(48) CHARACTER SET ascii NOT NULL,
    checked_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_check_ref (source_id,id),
    KEY ix_ds_check_source (source_id,checked_at,id),
    KEY ix_ds_check_actor (actor_id,expires_at),
    CONSTRAINT fk_ds_check_source FOREIGN KEY (source_id) REFERENCES ds_source (id),
    CONSTRAINT ck_ds_check_times CHECK (expires_at > checked_at)
);

-- 允许停用前记录当前源计划，实际计划始于同源首次成功。
CREATE TABLE ds_collection_schedule (
    source_id BIGINT UNSIGNED PRIMARY KEY,
    revision INT UNSIGNED NOT NULL,
    anchor_at DATETIME(3) NOT NULL,
    last_admitted_cycle_no BIGINT UNSIGNED NOT NULL DEFAULT 0,
    next_check_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY ix_ds_schedule_tick (next_check_at,source_id),
    CONSTRAINT fk_ds_schedule_source FOREIGN KEY (source_id) REFERENCES ds_source (id),
    CONSTRAINT ck_ds_schedule_revision CHECK (revision > 0)
);

CREATE TABLE ds_collection_task (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    start_command_id CHAR(36) CHARACTER SET ascii NOT NULL,
    task_no VARCHAR(40) CHARACTER SET ascii NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    config_revision_id BIGINT UNSIGNED NOT NULL,
    credential_revision_id BIGINT UNSIGNED NOT NULL,
    parent_task_id CHAR(36) CHARACTER SET ascii NULL,
    trigger_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    actor_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    actor_id BIGINT UNSIGNED NULL,
    actor_ref JSON NOT NULL,
    schedule_revision INT UNSIGNED NULL,
    schedule_cycle_no BIGINT UNSIGNED NULL,
    scheduled_for DATETIME(3) NULL,
    schedule_time_zone VARCHAR(64) CHARACTER SET ascii NULL,
    state VARCHAR(48) CHARACTER SET ascii NOT NULL,
    phase VARCHAR(48) CHARACTER SET ascii NULL,
    execution_state VARCHAR(48) CHARACTER SET ascii NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    run_version INT UNSIGNED NOT NULL DEFAULT 1,
    stream_epoch CHAR(36) CHARACTER SET ascii NOT NULL,
    lease_owner VARCHAR(64) CHARACTER SET ascii NULL,
    lease_until DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    queue_deadline_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    execution_deadline_at DATETIME(3) NULL,
    ended_at DATETIME(3) NULL,
    enumeration_complete BOOLEAN NOT NULL DEFAULT FALSE,
    discovered_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    complete_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    partial_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    total_count BIGINT UNSIGNED NULL,
    stop_requested_at DATETIME(3) NULL,
    cancel_requested_at DATETIME(3) NULL,
    stop_confirmed_at DATETIME(3) NULL,
    stop_next_check_at DATETIME(3) NULL,
    cancel_reason VARCHAR(500) NULL,
    failure_code VARCHAR(48) CHARACTER SET ascii NULL,
    failure_message VARCHAR(500) NULL,
    UNIQUE KEY uk_ds_task_no (task_no),
    UNIQUE KEY uk_ds_task_reference (source_id,id),
    UNIQUE KEY uk_ds_task_actor_command (actor_id,start_command_id),
    UNIQUE KEY uk_ds_task_schedule_cycle (source_id,schedule_revision,schedule_cycle_no),
    KEY ix_ds_task_source_created (source_id,created_at,id),
    KEY ix_ds_task_source_started (source_id,started_at,id),
    KEY ix_ds_task_lease (state,lease_until,id),
    KEY ix_ds_task_queue (state,queue_deadline_at,id),
    KEY ix_ds_task_stop (execution_state,stop_next_check_at,id),
    CONSTRAINT fk_ds_task_revision FOREIGN KEY (source_id,config_revision_id) REFERENCES ds_connection_revision (source_id,id),
    CONSTRAINT fk_ds_task_credential FOREIGN KEY (source_id,credential_revision_id) REFERENCES ds_credential_revision (source_id,id),
    CONSTRAINT fk_ds_task_previous FOREIGN KEY (source_id,parent_task_id) REFERENCES ds_collection_task (source_id,id),
    CONSTRAINT ck_ds_task_state CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_ds_task_execution CHECK (execution_state IN ('NOT_STARTED','RUNNING','STOP_REQUESTED','STOPPED','UNKNOWN')),
    CONSTRAINT ck_ds_task_version CHECK (version > 0 AND run_version > 0),
    CONSTRAINT ck_ds_task_trigger CHECK (
        (trigger_kind = 'SCHEDULED' AND actor_kind = 'SYSTEM' AND actor_id IS NULL
         AND schedule_revision > 0 AND schedule_cycle_no > 0 AND scheduled_for IS NOT NULL AND schedule_time_zone IS NOT NULL
         AND parent_task_id IS NULL)
        OR (trigger_kind IN ('MANUAL','RETRY') AND actor_kind = 'USER' AND actor_id IS NOT NULL
            AND schedule_revision IS NULL AND schedule_cycle_no IS NULL AND scheduled_for IS NULL AND schedule_time_zone IS NULL
            AND ((trigger_kind = 'RETRY') = (parent_task_id IS NOT NULL)))),
    CONSTRAINT ck_ds_task_counts CHECK (
        complete_count + partial_count + failed_count <= discovered_count
        AND ((enumeration_complete = 1 AND total_count IS NOT NULL AND total_count = discovered_count)
             OR (enumeration_complete = 0 AND total_count IS NULL))
        AND (state <> 'SUCCEEDED' OR (enumeration_complete = 1 AND complete_count = discovered_count
             AND partial_count = 0 AND failed_count = 0))),
    CONSTRAINT ck_ds_task_end CHECK (
        (state IN ('SUCCEEDED','FAILED','CANCELLED')) = (ended_at IS NOT NULL))
);

CREATE TABLE ds_collection_execution (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    run_version INT UNSIGNED NOT NULL,
    source_instance_key BINARY(32) NOT NULL,
    adapter_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    locator_envelope BLOB NOT NULL,
    state VARCHAR(48) CHARACTER SET ascii NOT NULL,
    registered_at DATETIME(3) NOT NULL,
    stop_requested_at DATETIME(3) NULL,
    last_verified_at DATETIME(3) NULL,
    stopped_at DATETIME(3) NULL,
    server_deadline_at DATETIME(3) NULL,
    stop_proof_kind VARCHAR(48) CHARACTER SET ascii NULL,
    KEY ix_ds_execution_task (task_id,run_version,state,id),
    CONSTRAINT fk_ds_execution_task FOREIGN KEY (task_id) REFERENCES ds_collection_task (id),
    CONSTRAINT ck_ds_execution_state CHECK (state IN ('REGISTERED','READING','STOP_REQUESTED','UNKNOWN','STOPPED')),
    CONSTRAINT ck_ds_execution_stop CHECK (state <> 'STOPPED' OR (stopped_at IS NOT NULL AND stop_proof_kind IS NOT NULL))
);

CREATE TABLE ds_connection_change (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    command_id CHAR(36) CHARACTER SET ascii NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    base_connection_revision_id BIGINT UNSIGNED NOT NULL,
    check_id BIGINT UNSIGNED NOT NULL,
    candidate_credential_revision_id BIGINT UNSIGNED NOT NULL,
    expected_source_version INT UNSIGNED NOT NULL,
    candidate_account VARCHAR(160) NOT NULL,
    review_evidence JSON NOT NULL,
    blocking_task_id CHAR(36) CHARACTER SET ascii NULL,
    applied_connection_revision_id BIGINT UNSIGNED NULL,
    state VARCHAR(48) CHARACTER SET ascii NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    stop_confirmed_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    failure_code VARCHAR(48) CHARACTER SET ascii NULL,
    failure_message VARCHAR(500) NULL,
    UNIQUE KEY uk_ds_rotation_actor_command (actor_id,command_id),
    UNIQUE KEY uk_ds_rotation_ref (source_id,id),
    KEY ix_ds_rotation_source (source_id,accepted_at,id),
    KEY ix_ds_rotation_recover (state,accepted_at,id),
    KEY ix_ds_rotation_task (blocking_task_id),
    CONSTRAINT fk_ds_rotation_base FOREIGN KEY (source_id,base_connection_revision_id) REFERENCES ds_connection_revision (source_id,id),
    CONSTRAINT fk_ds_rotation_candidate FOREIGN KEY (source_id,candidate_credential_revision_id)
        REFERENCES ds_credential_revision (source_id,id),
    CONSTRAINT fk_ds_rotation_check FOREIGN KEY (source_id,check_id) REFERENCES ds_capability_check (source_id,id),
    CONSTRAINT fk_ds_rotation_task FOREIGN KEY (source_id,blocking_task_id) REFERENCES ds_collection_task (source_id,id),
    CONSTRAINT fk_ds_rotation_applied FOREIGN KEY (source_id,applied_connection_revision_id) REFERENCES ds_connection_revision (source_id,id),
    CONSTRAINT ck_ds_rotation_status CHECK (
        state IN ('PENDING_STOP','APPLIED','FAILED') AND ((state = 'PENDING_STOP') = (completed_at IS NULL))
        AND (state <> 'APPLIED' OR (stop_confirmed_at IS NOT NULL AND applied_connection_revision_id IS NOT NULL)))
);

-- 当前目录和历史只能引用同源成功快照，终态与目录切换受同一个事务保护。
CREATE TABLE ds_snapshot (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    config_revision_id BIGINT UNSIGNED NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    scope_key BINARY(32) NOT NULL,
    comparison_contract_key BINARY(32) NOT NULL,
    adapter_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    comparison_version VARCHAR(64) CHARACTER SET ascii NOT NULL,
    previous_success_snapshot_id BIGINT UNSIGNED NULL,
    comparison_snapshot_id BIGINT UNSIGNED NULL,
    status VARCHAR(48) CHARACTER SET ascii NOT NULL,
    analysis VARCHAR(48) CHARACTER SET ascii NULL,
    new_change_count BIGINT UNSIGNED NULL,
    read_started_at DATETIME(3) NULL,
    read_ended_at DATETIME(3) NULL,
    committed_at DATETIME(3) NULL,
    UNIQUE KEY uk_ds_snapshot_task (task_id),
    UNIQUE KEY uk_ds_snapshot_ref (source_id,id),
    KEY ix_ds_snapshot_compare (source_id,scope_key,comparison_contract_key,status,committed_at,id),
    KEY ix_ds_snapshot_history (source_id,status,committed_at,id),
    CONSTRAINT fk_ds_snapshot_task FOREIGN KEY (source_id,task_id) REFERENCES ds_collection_task (source_id,id),
    CONSTRAINT fk_ds_snapshot_config FOREIGN KEY (source_id,config_revision_id) REFERENCES ds_connection_revision (source_id,id),
    CONSTRAINT fk_ds_snapshot_previous FOREIGN KEY (source_id,previous_success_snapshot_id) REFERENCES ds_snapshot (source_id,id),
    CONSTRAINT fk_ds_snapshot_comparison FOREIGN KEY (source_id,comparison_snapshot_id) REFERENCES ds_snapshot (source_id,id),
    CONSTRAINT ck_ds_snapshot_state CHECK (
        status IN ('CANDIDATE','SUCCEEDED','FAILED','CANCELLED') AND ((status = 'SUCCEEDED') = (committed_at IS NOT NULL))
        AND ((status = 'SUCCEEDED') = (analysis IS NOT NULL))),
    CONSTRAINT ck_ds_snapshot_comparison CHECK (
        analysis IS NULL
        OR (analysis = 'INITIAL_BASELINE' AND previous_success_snapshot_id IS NULL AND comparison_snapshot_id IS NULL)
        OR (analysis = 'NEW_SCOPE_BASELINE' AND previous_success_snapshot_id IS NOT NULL AND comparison_snapshot_id IS NULL)
        OR (analysis IN ('NO_CHANGE','CHANGES_FOUND') AND comparison_snapshot_id IS NOT NULL))
);

ALTER TABLE ds_source
    ADD CONSTRAINT fk_ds_source_revision FOREIGN KEY (id,current_connection_revision_id) REFERENCES ds_connection_revision (source_id,id),
    ADD CONSTRAINT fk_ds_source_task FOREIGN KEY (id,active_task_id) REFERENCES ds_collection_task (source_id,id),
    ADD CONSTRAINT fk_ds_source_pending FOREIGN KEY (id,pending_change_id) REFERENCES ds_connection_change (source_id,id),
    ADD CONSTRAINT fk_ds_source_snapshot FOREIGN KEY (id,current_snapshot_id) REFERENCES ds_snapshot (source_id,id);

-- 对象和字段的身份与不可变定义分开保存，快照只记录实际观测。
CREATE TABLE ds_object (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    generation INT UNSIGNED NOT NULL,
    source_identity_key BINARY(32) NULL,
    predecessor_object_id BIGINT UNSIGNED NULL,
    first_seen_at DATETIME(3) NOT NULL,
    retired_change_id BIGINT UNSIGNED NULL,
    retired_at DATETIME(3) NULL,
    UNIQUE KEY uk_ds_object_old_id (predecessor_object_id),
    UNIQUE KEY uk_ds_object_ref (source_id,id),
    KEY ix_ds_object_source_identity (source_id,source_identity_key,id),
    CONSTRAINT fk_ds_object_source FOREIGN KEY (source_id) REFERENCES ds_source (id),
    CONSTRAINT fk_ds_object_predecessor FOREIGN KEY (source_id,predecessor_object_id) REFERENCES ds_object (source_id,id),
    CONSTRAINT ck_ds_object_generation CHECK (generation > 0)
);

CREATE TABLE ds_object_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    object_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    namespace VARCHAR(160) NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    object_type VARCHAR(48) CHARACTER SET ascii NOT NULL,
    parent_object_id BIGINT UNSIGNED NULL,
    source_comment TEXT NULL,
    view_facts JSON NULL,
    definition_digest BINARY(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_structure_revision (object_id,revision),
    UNIQUE KEY uk_ds_structure_ref (object_id,id),
    KEY ix_ds_structure_parent (parent_object_id),
    CONSTRAINT fk_ds_structure_object FOREIGN KEY (object_id) REFERENCES ds_object (id),
    CONSTRAINT fk_ds_structure_parent FOREIGN KEY (parent_object_id) REFERENCES ds_object (id),
    CONSTRAINT ck_ds_structure_revision CHECK (revision > 0)
);

CREATE TABLE ds_column (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    object_id BIGINT UNSIGNED NOT NULL,
    KEY ix_ds_column_object (object_id,id),
    CONSTRAINT fk_ds_column_object FOREIGN KEY (object_id) REFERENCES ds_object (id)
);

CREATE TABLE ds_column_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    column_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    native_type TEXT NOT NULL,
    nullable BOOLEAN NOT NULL,
    default_expression TEXT NULL,
    generated_expression TEXT NULL,
    source_comment TEXT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_field_revision (column_id,revision),
    CONSTRAINT fk_ds_field_column FOREIGN KEY (column_id) REFERENCES ds_column (id),
    CONSTRAINT ck_ds_field_values CHECK (revision > 0 AND ordinal > 0 AND nullable IN (0,1))
);

CREATE TABLE ds_object_column (
    object_revision_id BIGINT UNSIGNED NOT NULL,
    column_revision_id BIGINT UNSIGNED NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    PRIMARY KEY (object_revision_id,ordinal),
    UNIQUE KEY uk_ds_member_field (object_revision_id,column_revision_id),
    KEY ix_ds_member_field (column_revision_id),
    CONSTRAINT fk_ds_member_object FOREIGN KEY (object_revision_id) REFERENCES ds_object_revision (id),
    CONSTRAINT fk_ds_member_column FOREIGN KEY (column_revision_id) REFERENCES ds_column_revision (id)
);

CREATE TABLE ds_constraint (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    object_revision_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    source_name VARCHAR(160) NULL,
    target_object_ref JSON NULL,
    KEY ix_ds_constraint_object (object_revision_id,id),
    CONSTRAINT fk_ds_constraint_object FOREIGN KEY (object_revision_id) REFERENCES ds_object_revision (id)
);

CREATE TABLE ds_constraint_member (
    constraint_id BIGINT UNSIGNED NOT NULL,
    column_revision_id BIGINT UNSIGNED NULL,
    ordinal INT UNSIGNED NOT NULL,
    target_column_ref JSON NULL,
    expression TEXT NULL,
    sort_direction VARCHAR(48) CHARACTER SET ascii NULL,
    PRIMARY KEY (constraint_id,ordinal),
    KEY ix_ds_constraint_member_column (column_revision_id),
    CONSTRAINT fk_ds_constraint_member_object FOREIGN KEY (constraint_id) REFERENCES ds_constraint (id),
    CONSTRAINT fk_ds_constraint_member_column FOREIGN KEY (column_revision_id) REFERENCES ds_column_revision (id)
);

CREATE TABLE ds_snapshot_object (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT UNSIGNED NOT NULL,
    object_path_key BINARY(32) NOT NULL,
    namespace VARCHAR(160) NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    object_type VARCHAR(48) CHARACTER SET ascii NOT NULL,
    read_status VARCHAR(48) CHARACTER SET ascii NOT NULL,
    observed_identity VARCHAR(160) NULL,
    identity_evidence JSON NULL,
    object_id BIGINT UNSIGNED NULL,
    object_revision_id BIGINT UNSIGNED NULL,
    identity_change_id BIGINT UNSIGNED NULL,
    definition_digest BINARY(32) NULL,
    candidate_structure JSON NULL,
    failure_code VARCHAR(48) CHARACTER SET ascii NULL,
    failure_message VARCHAR(500) NULL,
    UNIQUE KEY uk_ds_observation_path (snapshot_id,object_path_key),
    KEY ix_ds_observation_identity (object_id,snapshot_id),
    KEY ix_ds_observation_change (identity_change_id),
    CONSTRAINT fk_ds_observation_snapshot FOREIGN KEY (snapshot_id) REFERENCES ds_snapshot (id),
    CONSTRAINT fk_ds_observation_object FOREIGN KEY (object_id) REFERENCES ds_object (id),
    CONSTRAINT fk_ds_observation_revision FOREIGN KEY (object_id,object_revision_id) REFERENCES ds_object_revision (object_id,id)
);

CREATE TABLE ds_attribute_result (
    snapshot_object_id BIGINT UNSIGNED NOT NULL,
    target_key BINARY(32) NOT NULL,
    target_name VARCHAR(160) NULL,
    attribute_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    read_status VARCHAR(48) CHARACTER SET ascii NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii NULL,
    reason_message VARCHAR(500) NULL,
    PRIMARY KEY (snapshot_object_id,target_key,attribute_kind),
    CONSTRAINT fk_ds_attribute_observation FOREIGN KEY (snapshot_object_id) REFERENCES ds_snapshot_object (id),
    CONSTRAINT ck_ds_attribute_status CHECK (read_status IN ('READ_VALUE','READ_EMPTY','NOT_APPLICABLE','UNSUPPORTED','FAILED','UNVERIFIED'))
);

-- 技术事实、人工核实与下游引用独立存放，历史证据保留原快照。
CREATE TABLE ds_change (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    discovery_task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    before_snapshot_id BIGINT UNSIGNED NOT NULL,
    after_snapshot_id BIGINT UNSIGNED NOT NULL,
    before_observation_id BIGINT UNSIGNED NULL,
    after_observation_id BIGINT UNSIGNED NULL,
    comparison_object_id BIGINT UNSIGNED NULL,
    object_id BIGINT UNSIGNED NULL,
    column_id BIGINT UNSIGNED NULL,
    change_key BINARY(32) NOT NULL,
    discovery_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    change_type VARCHAR(48) CHARACTER SET ascii NOT NULL,
    target_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    attribute_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    fact_state VARCHAR(48) CHARACTER SET ascii NOT NULL,
    stage VARCHAR(48) CHARACTER SET ascii NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    evidence JSON NOT NULL,
    successor_change_id BIGINT UNSIGNED NULL,
    visibility_restored_snapshot_id BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    discovered_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_change_evidence (discovery_task_id,change_key),
    UNIQUE KEY uk_ds_change_ref (source_id,id),
    KEY ix_ds_change_source_stage (source_id,stage,discovered_at,id),
    KEY ix_ds_change_source_type (source_id,change_type,discovered_at,id),
    KEY ix_ds_change_basis (source_id,comparison_object_id,stage,id),
    KEY ix_ds_change_object (source_id,object_id,stage,id),
    KEY ix_ds_change_successor (successor_change_id),
    CONSTRAINT fk_ds_change_task FOREIGN KEY (source_id,discovery_task_id) REFERENCES ds_collection_task (source_id,id),
    CONSTRAINT fk_ds_change_before FOREIGN KEY (source_id,before_snapshot_id) REFERENCES ds_snapshot (source_id,id),
    CONSTRAINT fk_ds_change_after FOREIGN KEY (source_id,after_snapshot_id) REFERENCES ds_snapshot (source_id,id),
    CONSTRAINT fk_ds_change_before_evidence FOREIGN KEY (before_observation_id) REFERENCES ds_snapshot_object (id),
    CONSTRAINT fk_ds_change_after_evidence FOREIGN KEY (after_observation_id) REFERENCES ds_snapshot_object (id),
    CONSTRAINT fk_ds_change_basis FOREIGN KEY (source_id,comparison_object_id) REFERENCES ds_object (source_id,id),
    CONSTRAINT fk_ds_change_object FOREIGN KEY (source_id,object_id) REFERENCES ds_object (source_id,id),
    CONSTRAINT fk_ds_change_column FOREIGN KEY (column_id) REFERENCES ds_column (id),
    CONSTRAINT fk_ds_change_successor FOREIGN KEY (source_id,successor_change_id) REFERENCES ds_change (source_id,id),
    CONSTRAINT fk_ds_change_restore FOREIGN KEY (source_id,visibility_restored_snapshot_id) REFERENCES ds_snapshot (source_id,id)
);

ALTER TABLE ds_object
    ADD CONSTRAINT fk_ds_object_retired FOREIGN KEY (source_id,retired_change_id) REFERENCES ds_change (source_id,id);
ALTER TABLE ds_snapshot_object
    ADD CONSTRAINT fk_ds_observation_resolution FOREIGN KEY (identity_change_id) REFERENCES ds_change (id);

CREATE TABLE ds_change_review (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    change_id BIGINT UNSIGNED NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii NULL,
    actor_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    actor_id BIGINT UNSIGNED NULL,
    reviewed_version INT UNSIGNED NOT NULL,
    decision VARCHAR(48) CHARACTER SET ascii NOT NULL,
    evidence_text VARCHAR(1000) NOT NULL,
    verified_observation_id BIGINT UNSIGNED NULL,
    resolved_object_id BIGINT UNSIGNED NULL,
    resolved_object_revision_id BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ds_review_decision (change_id,reviewed_version),
    UNIQUE KEY uk_ds_review_command (actor_id,command_id),
    CONSTRAINT fk_ds_review_change FOREIGN KEY (source_id,change_id) REFERENCES ds_change (source_id,id),
    CONSTRAINT fk_ds_review_evidence FOREIGN KEY (verified_observation_id) REFERENCES ds_snapshot_object (id),
    CONSTRAINT fk_ds_review_object FOREIGN KEY (source_id,resolved_object_id) REFERENCES ds_object (source_id,id),
    CONSTRAINT fk_ds_review_revision FOREIGN KEY (resolved_object_id,resolved_object_revision_id) REFERENCES ds_object_revision (object_id,id)
);

CREATE TABLE ds_change_impact_ref (
    change_id BIGINT UNSIGNED NOT NULL,
    ref_kind VARCHAR(48) CHARACTER SET ascii NOT NULL,
    ref_id BIGINT UNSIGNED NOT NULL,
    linked_at DATETIME(3) NOT NULL,
    PRIMARY KEY (change_id,ref_kind,ref_id),
    KEY ix_ds_impact_ref (ref_kind,ref_id,change_id),
    CONSTRAINT fk_ds_impact_change FOREIGN KEY (change_id) REFERENCES ds_change (id),
    CONSTRAINT ck_ds_impact_kind CHECK (ref_kind IN ('GOVERNANCE_ITEM','KNOWLEDGE_WORK','USAGE_CONTROL'))
);
