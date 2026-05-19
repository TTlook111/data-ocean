-- 元数据采集模块表结构
-- metadata_snapshot, db_table_meta, db_column_meta, table_relation, schema_sync_task, schema_change_event

-- 同步任务表
CREATE TABLE schema_sync_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    trigger_type VARCHAR(20) NOT NULL COMMENT '触发类型: MANUAL/SCHEDULED',
    triggered_by BIGINT DEFAULT NULL COMMENT '触发人（定时任务为NULL）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT',
    progress_total INT DEFAULT NULL COMMENT '总表数',
    progress_current INT DEFAULT 0 COMMENT '已处理表数',
    snapshot_id BIGINT DEFAULT NULL COMMENT '生成的快照ID',
    error_message TEXT DEFAULT NULL COMMENT '失败原因',
    started_at DATETIME DEFAULT NULL COMMENT '开始时间',
    finished_at DATETIME DEFAULT NULL COMMENT '完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_ds_status (datasource_id, status),
    INDEX idx_created (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Schema同步任务';

-- 元数据快照主表
CREATE TABLE metadata_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    snapshot_version INT NOT NULL COMMENT '版本号（同一数据源递增）',
    schema_hash VARCHAR(64) NOT NULL COMMENT 'Schema内容MD5',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/CHECKING/ISSUE_FOUND/APPROVED/PUBLISHED/EXPIRED',
    table_count INT NOT NULL DEFAULT 0 COMMENT '表数量',
    column_count INT NOT NULL DEFAULT 0 COMMENT '字段总数',
    total_rows_estimate BIGINT DEFAULT NULL COMMENT '总行数估算',
    quality_score DECIMAL(5,2) DEFAULT NULL COMMENT '质量分',
    sync_task_id BIGINT DEFAULT NULL COMMENT '关联同步任务ID',
    reviewed_by BIGINT DEFAULT NULL COMMENT '审核人',
    reviewed_at DATETIME DEFAULT NULL COMMENT '审核时间',
    published_at DATETIME DEFAULT NULL COMMENT '发布时间',
    expired_at DATETIME DEFAULT NULL COMMENT '过期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_ds_version (datasource_id, snapshot_version DESC),
    INDEX idx_ds_status (datasource_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元数据快照';

-- 表元数据
CREATE TABLE db_table_meta (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL COMMENT '关联快照ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(200) NOT NULL COMMENT '表名',
    table_comment VARCHAR(1000) DEFAULT NULL COMMENT '表注释',
    table_type VARCHAR(20) NOT NULL DEFAULT 'TABLE' COMMENT '类型: TABLE/VIEW',
    engine VARCHAR(50) DEFAULT NULL COMMENT '存储引擎',
    table_charset VARCHAR(50) DEFAULT NULL COMMENT '字符集',
    row_count_estimate BIGINT DEFAULT NULL COMMENT '行数估算',
    data_size_bytes BIGINT DEFAULT NULL COMMENT '数据大小(字节)',
    index_size_bytes BIGINT DEFAULT NULL COMMENT '索引大小(字节)',
    governance_status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED' COMMENT '治理状态',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_snapshot_table (snapshot_id, table_name),
    INDEX idx_ds_table (datasource_id, table_name),
    INDEX idx_governance (governance_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='表元数据';

-- 字段元数据
CREATE TABLE db_column_meta (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL COMMENT '关联快照ID',
    table_meta_id BIGINT NOT NULL COMMENT '关联表ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(200) NOT NULL COMMENT '表名',
    column_name VARCHAR(200) NOT NULL COMMENT '字段名',
    column_comment VARCHAR(1000) DEFAULT NULL COMMENT '字段注释',
    data_type VARCHAR(100) NOT NULL COMMENT '数据类型',
    is_nullable TINYINT NOT NULL DEFAULT 1 COMMENT '是否可空',
    column_default VARCHAR(500) DEFAULT NULL COMMENT '默认值',
    is_primary_key TINYINT NOT NULL DEFAULT 0 COMMENT '是否主键',
    ordinal_position INT NOT NULL COMMENT '字段顺序',
    null_rate DECIMAL(5,4) DEFAULT NULL COMMENT '空值率',
    distinct_count BIGINT DEFAULT NULL COMMENT '去重值数量',
    enum_top_values JSON DEFAULT NULL COMMENT '枚举TopN值',
    min_value VARCHAR(500) DEFAULT NULL COMMENT '最小值',
    max_value VARCHAR(500) DEFAULT NULL COMMENT '最大值',
    governance_status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED' COMMENT '治理状态',
    confidence_score INT DEFAULT NULL COMMENT '可信度(0-100)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_snapshot_table_col (snapshot_id, table_name, column_name),
    INDEX idx_table_meta (table_meta_id),
    INDEX idx_governance (governance_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段元数据';

-- 表关联关系
CREATE TABLE table_relation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL COMMENT '关联快照ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    source_table VARCHAR(200) NOT NULL COMMENT '源表',
    source_column VARCHAR(200) NOT NULL COMMENT '源字段',
    target_table VARCHAR(200) NOT NULL COMMENT '目标表',
    target_column VARCHAR(200) NOT NULL COMMENT '目标字段',
    relation_type VARCHAR(20) NOT NULL COMMENT '关系类型: FK/INFERRED/MANUAL',
    confidence DECIMAL(3,2) DEFAULT 1.00 COMMENT '关系置信度',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_snapshot_source (snapshot_id, source_table),
    INDEX idx_snapshot_target (snapshot_id, target_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='表关联关系';

-- Schema变更事件
CREATE TABLE schema_change_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    old_snapshot_id BIGINT NOT NULL COMMENT '旧快照ID',
    new_snapshot_id BIGINT NOT NULL COMMENT '新快照ID',
    change_type VARCHAR(30) NOT NULL COMMENT '变更类型: TABLE_ADDED/TABLE_REMOVED/COLUMN_ADDED/COLUMN_REMOVED/COLUMN_TYPE_CHANGED/COMMENT_CHANGED',
    table_name VARCHAR(200) NOT NULL COMMENT '涉及表',
    column_name VARCHAR(200) DEFAULT NULL COMMENT '涉及字段',
    old_value VARCHAR(500) DEFAULT NULL COMMENT '旧值',
    new_value VARCHAR(500) DEFAULT NULL COMMENT '新值',
    risk_level VARCHAR(10) NOT NULL DEFAULT 'LOW' COMMENT '风险等级: HIGH/MEDIUM/LOW',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_ds_snapshots (datasource_id, new_snapshot_id),
    INDEX idx_risk (risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Schema变更事件';
