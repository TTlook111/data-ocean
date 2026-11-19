-- 治理条目按成功目录中的稳定身份组织，业务修订与来源快照分开。
CREATE TABLE gov_item (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    object_id BIGINT UNSIGNED NOT NULL,
    generation INT UNSIGNED NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    head_revision_id BIGINT UNSIGNED NULL,
    last_confirmed_revision_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_target (source_id,kind,target_id),
    KEY ix_gov_object (source_id,object_id,kind,target_id),
    CONSTRAINT ck_gov_target CHECK (kind='COLUMN' OR (kind='TABLE' AND target_id=object_id))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 固定正文、创建时的结构依据及可追溯的上一修订。
CREATE TABLE gov_item_revision (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    revision INT UNSIGNED NOT NULL,
    previous_revision_id BIGINT UNSIGNED NULL,
    content JSON NOT NULL,
    origin VARCHAR(16) CHARACTER SET ascii NOT NULL,
    suggestion_id BIGINT UNSIGNED NULL,
    snapshot_id BIGINT UNSIGNED NOT NULL,
    object_revision_id BIGINT UNSIGNED NOT NULL,
    column_revision_id BIGINT UNSIGNED NULL,
    changed_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_item_revision (item_id,revision),
    CONSTRAINT fk_gov_revision_item FOREIGN KEY (item_id) REFERENCES gov_item(id),
    CONSTRAINT fk_gov_revision_previous FOREIGN KEY (previous_revision_id) REFERENCES gov_item_revision(id),
    CONSTRAINT ck_gov_revision_origin CHECK (origin IN ('MANUAL','AI_ASSISTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 人工确认可绑定同一内容的多次复核，确认移除保存最后成功结构。
CREATE TABLE gov_confirmation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    item_revision_id BIGINT UNSIGNED NULL,
    action VARCHAR(16) CHARACTER SET ascii NOT NULL,
    snapshot_id BIGINT UNSIGNED NOT NULL,
    object_revision_id BIGINT UNSIGNED NOT NULL,
    column_revision_id BIGINT UNSIGNED NULL,
    command_id CHAR(36) CHARACTER SET ascii NOT NULL,
    confirmed_by BIGINT UNSIGNED NOT NULL,
    confirmed_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_confirmation_command (confirmed_by,command_id),
    KEY ix_gov_confirmation_item (item_id,confirmed_at,id),
    CONSTRAINT fk_gov_confirm_item FOREIGN KEY (item_id) REFERENCES gov_item(id),
    CONSTRAINT fk_gov_confirm_revision FOREIGN KEY (item_revision_id) REFERENCES gov_item_revision(id),
    CONSTRAINT ck_gov_confirm_action CHECK (action='ACK_REMOVAL' OR (action='CONFIRM' AND item_revision_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 保持实际出处可反查，业务说明原件仍由数据源模块保存。
CREATE TABLE gov_evidence_ref (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    item_revision_id BIGINT UNSIGNED NULL,
    suggestion_id BIGINT UNSIGNED NULL,
    content_path VARCHAR(48) CHARACTER SET ascii NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    locator_key CHAR(64) CHARACTER SET ascii NOT NULL,
    snapshot_id BIGINT UNSIGNED NULL,
    object_id BIGINT UNSIGNED NULL,
    object_revision_id BIGINT UNSIGNED NULL,
    column_id BIGINT UNSIGNED NULL,
    column_revision_id BIGINT UNSIGNED NULL,
    note_id BIGINT UNSIGNED NULL,
    note_revision_id BIGINT UNSIGNED NULL,
    attachment_id BIGINT UNSIGNED NULL,
    location JSON NULL,
    manual_basis VARCHAR(1000) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_evidence_item (item_revision_id,content_path,locator_key),
    UNIQUE KEY uk_gov_evidence_suggestion (suggestion_id,content_path,locator_key),
    KEY ix_gov_evidence_note (source_id,note_id,note_revision_id),
    KEY ix_gov_evidence_structure (source_id,object_id,column_id),
    CONSTRAINT fk_gov_evidence_revision FOREIGN KEY (item_revision_id) REFERENCES gov_item_revision(id),
    CONSTRAINT ck_gov_evidence_owner CHECK ((item_revision_id IS NULL) <> (suggestion_id IS NULL)),
    CONSTRAINT ck_gov_evidence_kind CHECK (
        (kind='NOTE' AND note_id IS NOT NULL AND note_revision_id IS NOT NULL AND location IS NOT NULL)
        OR (kind='STRUCTURE' AND snapshot_id IS NOT NULL AND object_id IS NOT NULL AND object_revision_id IS NOT NULL)
        OR (kind='MANUAL' AND manual_basis IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 关键缺口保留首次问题及实际写入的核清结论。
CREATE TABLE gov_gap (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    found_revision_id BIGINT UNSIGNED NULL,
    suggestion_id BIGINT UNSIGNED NULL,
    content_path VARCHAR(48) CHARACTER SET ascii NOT NULL,
    question VARCHAR(1000) NOT NULL,
    origin VARCHAR(20) CHARACTER SET ascii NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii NOT NULL,
    resolved_revision_id BIGINT UNSIGNED NULL,
    resolution VARCHAR(1000) NULL,
    opened_by BIGINT UNSIGNED NOT NULL,
    opened_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    resolved_by BIGINT UNSIGNED NULL,
    resolved_at DATETIME(3) NULL,
    KEY ix_gov_gap_item (item_id,state,id),
    CONSTRAINT fk_gov_gap_item FOREIGN KEY (item_id) REFERENCES gov_item(id),
    CONSTRAINT ck_gov_gap_state CHECK (state IN ('OPEN','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 资料状态版本区分同一资料同一内容的多次撤下。
CREATE TABLE gov_note_change (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    note_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(20) CHARACTER SET ascii NOT NULL,
    note_version INT UNSIGNED NOT NULL,
    before_revision_id BIGINT UNSIGNED NOT NULL,
    after_revision_id BIGINT UNSIGNED NULL,
    mapping_state VARCHAR(16) CHARACTER SET ascii NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    occurred_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_note_change (note_id,note_version),
    KEY ix_gov_note_mapping (source_id,mapping_state,occurred_at,id),
    CONSTRAINT ck_gov_note_change CHECK (
        (kind='CONTENT_CHANGED' AND after_revision_id IS NOT NULL)
        OR (kind='WITHDRAWN' AND after_revision_id IS NULL)),
    CONSTRAINT ck_gov_note_mapping CHECK (mapping_state IN ('MAPPED','NEEDS_MAPPING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 每条来源和目标只产生一笔影响，确认时记录真实覆盖范围。
CREATE TABLE gov_impact_task (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    item_id BIGINT UNSIGNED NOT NULL,
    structure_change_id BIGINT UNSIGNED NULL,
    note_change_id BIGINT UNSIGNED NULL,
    baseline_revision_id BIGINT UNSIGNED NULL,
    impact_kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    started_at DATETIME(3) NULL,
    confirmation_id BIGINT UNSIGNED NULL,
    resolved_at DATETIME(3) NULL,
    knowledge_required BOOLEAN NULL,
    UNIQUE KEY uk_gov_structure_impact (structure_change_id,item_id),
    UNIQUE KEY uk_gov_note_impact (note_change_id,item_id),
    KEY ix_gov_impact_item (item_id,state,id),
    CONSTRAINT fk_gov_impact_item FOREIGN KEY (item_id) REFERENCES gov_item(id),
    CONSTRAINT fk_gov_impact_note FOREIGN KEY (note_change_id) REFERENCES gov_note_change(id),
    CONSTRAINT fk_gov_impact_confirmation FOREIGN KEY (confirmation_id) REFERENCES gov_confirmation(id),
    CONSTRAINT ck_gov_impact_source CHECK ((structure_change_id IS NULL) <> (note_change_id IS NULL)),
    CONSTRAINT ck_gov_impact_kind CHECK (impact_kind IN ('NEW_ITEM','RECHECK','REMOVAL')),
    CONSTRAINT ck_gov_impact_state CHECK (state IN ('PENDING','IN_PROGRESS','REVIEWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 风险阻断有自己的当前状态和追加式触发历史。
CREATE TABLE gov_usage_control (
    item_id BIGINT UNSIGNED PRIMARY KEY,
    blocked BOOLEAN NOT NULL,
    version INT UNSIGNED NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    CONSTRAINT fk_gov_control_item FOREIGN KEY (item_id) REFERENCES gov_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE gov_usage_control_event (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT UNSIGNED NOT NULL,
    control_version INT UNSIGNED NOT NULL,
    action VARCHAR(12) CHARACTER SET ascii NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii NOT NULL,
    reason VARCHAR(500) NOT NULL,
    structure_change_id BIGINT UNSIGNED NULL,
    command_id CHAR(36) CHARACTER SET ascii NULL,
    actor_kind VARCHAR(8) CHARACTER SET ascii NOT NULL,
    actor_id BIGINT UNSIGNED NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_gov_control_version (item_id,control_version),
    UNIQUE KEY uk_gov_control_structure (item_id,structure_change_id),
    CONSTRAINT fk_gov_control_event FOREIGN KEY (item_id) REFERENCES gov_usage_control(item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
