# Data Model: 元数据采集模块

**Date**: 2026-05-16

## Tables

### metadata_snapshot（元数据快照主表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 关联数据源 |
| snapshot_version | INT | NO | | 版本号（同一数据源递增） |
| schema_hash | VARCHAR(64) | NO | | Schema 内容 MD5（快速变更检测） |
| status | VARCHAR(20) | NO | 'DRAFT' | DRAFT/CHECKING/ISSUE_FOUND/APPROVED/PUBLISHED/EXPIRED |
| table_count | INT | NO | 0 | 表数量 |
| column_count | INT | NO | 0 | 字段总数 |
| total_rows_estimate | BIGINT | YES | NULL | 总行数估算 |
| quality_score | DECIMAL(5,2) | YES | NULL | 质量分（治理模块填写） |
| sync_task_id | BIGINT | YES | NULL | 关联同步任务 |
| reviewed_by | BIGINT | YES | NULL | 审核人 |
| reviewed_at | DATETIME | YES | NULL | 审核时间 |
| published_at | DATETIME | YES | NULL | 发布时间 |
| expired_at | DATETIME | YES | NULL | 过期时间 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_ds_version` (datasource_id, snapshot_version DESC)
- INDEX `idx_ds_status` (datasource_id, status)

---

### db_table_meta（表元数据）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| datasource_id | BIGINT | NO | | 数据源（冗余，便于查询） |
| table_name | VARCHAR(200) | NO | | 表名 |
| table_comment | VARCHAR(1000) | YES | NULL | 表注释 |
| table_type | VARCHAR(20) | NO | 'TABLE' | TABLE/VIEW |
| engine | VARCHAR(50) | YES | NULL | 存储引擎 |
| charset | VARCHAR(50) | YES | NULL | 字符集 |
| row_count_estimate | BIGINT | YES | NULL | 行数估算 |
| data_size_bytes | BIGINT | YES | NULL | 数据大小 |
| index_size_bytes | BIGINT | YES | NULL | 索引大小 |
| governance_status | VARCHAR(20) | NO | 'DISCOVERED' | 治理状态 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_snapshot_table` (snapshot_id, table_name)
- INDEX `idx_ds_table` (datasource_id, table_name)
- INDEX `idx_governance` (governance_status)

---

### db_column_meta（字段元数据）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| table_meta_id | BIGINT | NO | | 关联表 |
| datasource_id | BIGINT | NO | | 数据源（冗余） |
| table_name | VARCHAR(200) | NO | | 表名（冗余） |
| column_name | VARCHAR(200) | NO | | 字段名 |
| column_comment | VARCHAR(1000) | YES | NULL | 字段注释 |
| data_type | VARCHAR(100) | NO | | 数据类型（如 VARCHAR(255)） |
| is_nullable | TINYINT | NO | 1 | 是否可空 |
| column_default | VARCHAR(500) | YES | NULL | 默认值 |
| is_primary_key | TINYINT | NO | 0 | 是否主键 |
| ordinal_position | INT | NO | | 字段顺序 |
| null_rate | DECIMAL(5,4) | YES | NULL | 空值率（0.0000-1.0000） |
| distinct_count | BIGINT | YES | NULL | 去重值数量 |
| enum_top_values | JSON | YES | NULL | 枚举 TopN 值 |
| min_value | VARCHAR(500) | YES | NULL | 最小值 |
| max_value | VARCHAR(500) | YES | NULL | 最大值 |
| governance_status | VARCHAR(20) | NO | 'DISCOVERED' | 治理状态 |
| confidence_score | INT | YES | NULL | 可信度（0-100） |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_snapshot_table_col` (snapshot_id, table_name, column_name)
- INDEX `idx_table_meta` (table_meta_id)
- INDEX `idx_governance` (governance_status)

---

### table_relation（表关联关系）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| datasource_id | BIGINT | NO | | 数据源 |
| source_table | VARCHAR(200) | NO | | 源表 |
| source_column | VARCHAR(200) | NO | | 源字段 |
| target_table | VARCHAR(200) | NO | | 目标表 |
| target_column | VARCHAR(200) | NO | | 目标字段 |
| relation_type | VARCHAR(20) | NO | | FK/INFERRED/MANUAL |
| confidence | DECIMAL(3,2) | YES | 1.00 | 关系置信度 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_snapshot_source` (snapshot_id, source_table)
- INDEX `idx_snapshot_target` (snapshot_id, target_table)

---

### schema_sync_task（同步任务表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 数据源 |
| trigger_type | VARCHAR(20) | NO | | MANUAL/SCHEDULED |
| triggered_by | BIGINT | YES | NULL | 触发人（定时任务为 NULL） |
| status | VARCHAR(20) | NO | 'PENDING' | PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT |
| progress_total | INT | YES | NULL | 总表数 |
| progress_current | INT | YES | 0 | 已处理表数 |
| snapshot_id | BIGINT | YES | NULL | 生成的快照 ID |
| error_message | TEXT | YES | NULL | 失败原因 |
| started_at | DATETIME | YES | NULL | 开始时间 |
| finished_at | DATETIME | YES | NULL | 完成时间 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_ds_status` (datasource_id, status)
- INDEX `idx_created` (created_at DESC)

---

### schema_change_event（Schema 变更事件）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 数据源 |
| old_snapshot_id | BIGINT | NO | | 旧快照 |
| new_snapshot_id | BIGINT | NO | | 新快照 |
| change_type | VARCHAR(30) | NO | | TABLE_ADDED/TABLE_REMOVED/COLUMN_ADDED/COLUMN_REMOVED/COLUMN_TYPE_CHANGED/COMMENT_CHANGED |
| table_name | VARCHAR(200) | NO | | 涉及表 |
| column_name | VARCHAR(200) | YES | NULL | 涉及字段 |
| old_value | VARCHAR(500) | YES | NULL | 旧值 |
| new_value | VARCHAR(500) | YES | NULL | 新值 |
| risk_level | VARCHAR(10) | NO | 'LOW' | HIGH/MEDIUM/LOW |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_ds_snapshots` (datasource_id, new_snapshot_id)
- INDEX `idx_risk` (risk_level)

## Relationships

```
datasource 1 ──── N metadata_snapshot
datasource 1 ──── N schema_sync_task
metadata_snapshot 1 ──── N db_table_meta
metadata_snapshot 1 ──── N db_column_meta
metadata_snapshot 1 ──── N table_relation
metadata_snapshot 1 ──── N schema_change_event (as new_snapshot)
db_table_meta 1 ──── N db_column_meta
schema_sync_task 1 ──── 0..1 metadata_snapshot
```

## State Transitions

### schema_sync_task.status

```
PENDING → RUNNING → SUCCESS
                  → FAILED
                  → TIMEOUT (30min 无响应)
```

### metadata_snapshot.status (完整生命周期，跨模块)

```
DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED
                 → APPROVED (无问题直接通过)
```

- 采集模块创建快照时状态为 DRAFT
- 后续状态流转由治理模块(004)和版本模块(005)负责
