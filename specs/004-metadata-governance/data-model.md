# Data Model: 元数据治理模块

**Date**: 2026-05-16

## Tables

### metadata_quality_rule（质量规则表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| rule_code | VARCHAR(50) | NO | | 规则编码（唯一） |
| rule_name | VARCHAR(200) | NO | | 规则名称 |
| dimension | VARCHAR(20) | NO | | 校验维度：COMPLETENESS/ACCURACY/CONSISTENCY/TIMELINESS/TRACEABILITY |
| severity | VARCHAR(10) | NO | | 严重级别：HIGH/MEDIUM/LOW |
| description | VARCHAR(500) | YES | NULL | 规则描述 |
| check_target | VARCHAR(20) | NO | | 校验目标：TABLE/COLUMN/RELATION |
| enabled | TINYINT | NO | 1 | 是否启用 |
| deduction_points | DECIMAL(4,1) | NO | | 扣分值 |
| builtin | TINYINT | NO | 1 | 是否内置规则 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |
| updated_at | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_rule_code` (rule_code)
- INDEX `idx_dimension` (dimension)

---

### metadata_quality_issue（质量问题清单）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| datasource_id | BIGINT | NO | | 数据源 |
| rule_id | BIGINT | NO | | 触发的规则 |
| dimension | VARCHAR(20) | NO | | 校验维度 |
| severity | VARCHAR(10) | NO | | 严重级别 |
| table_name | VARCHAR(200) | NO | | 涉及表 |
| column_name | VARCHAR(200) | YES | NULL | 涉及字段（表级问题为 NULL） |
| issue_description | VARCHAR(1000) | NO | | 问题描述 |
| suggestion | VARCHAR(1000) | YES | NULL | 修正建议 |
| status | VARCHAR(20) | NO | 'OPEN' | OPEN/CONFIRMED/RESOLVED/REJECTED/AUTO_CLOSED |
| assignee_id | BIGINT | YES | NULL | 分派负责人 |
| resolved_by | BIGINT | YES | NULL | 解决人 |
| resolved_at | DATETIME | YES | NULL | 解决时间 |
| resolution_note | VARCHAR(500) | YES | NULL | 解决备注 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |
| updated_at | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_snapshot_status` (snapshot_id, status)
- INDEX `idx_ds_dimension` (datasource_id, dimension)
- INDEX `idx_assignee` (assignee_id, status)
- INDEX `idx_severity` (severity, status)

---

### metadata_review_record（治理审核记录）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| datasource_id | BIGINT | NO | | 数据源 |
| target_type | VARCHAR(20) | NO | | TABLE/COLUMN |
| table_name | VARCHAR(200) | NO | | 表名 |
| column_name | VARCHAR(200) | YES | NULL | 字段名 |
| action | VARCHAR(30) | NO | | 操作类型 |
| old_status | VARCHAR(20) | YES | NULL | 变更前状态 |
| new_status | VARCHAR(20) | YES | NULL | 变更后状态 |
| operator_id | BIGINT | NO | | 操作人 |
| remark | VARCHAR(500) | YES | NULL | 备注 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_snapshot` (snapshot_id)
- INDEX `idx_ds_table` (datasource_id, table_name)
- INDEX `idx_operator` (operator_id, created_at DESC)

**action 枚举值**:
- STATUS_CHANGE: 治理状态变更
- ISSUE_CONFIRM: 问题确认
- ISSUE_RESOLVE: 问题解决
- ISSUE_REJECT: 问题驳回
- BATCH_STATUS_CHANGE: 批量状态变更
- QUALITY_CHECK_TRIGGER: 触发质量校验

## Relationships

```
metadata_snapshot 1 ──── N metadata_quality_issue
metadata_quality_rule 1 ──── N metadata_quality_issue
metadata_snapshot 1 ──── N metadata_review_record
```

**跨模块关联**:
- metadata_quality_issue.snapshot_id → metadata_snapshot.id (003模块)
- 治理状态存储在 db_table_meta.governance_status / db_column_meta.governance_status (003模块表)

## State Transitions

### metadata_quality_issue.status

```
OPEN → CONFIRMED → RESOLVED (修正完成)
     → REJECTED (误报驳回)
     → AUTO_CLOSED (新快照覆盖)
```

- OPEN: 质量校验自动生成
- CONFIRMED: 负责人确认问题存在
- RESOLVED: 问题已修正（补充注释、修正类型等）
- REJECTED: 误报，说明理由后驳回
- AUTO_CLOSED: 新快照生成时自动关闭旧快照的未处理问题

### governance_status (表/字段级)

```
DISCOVERED → NORMAL (确认可用)
           → RECOMMENDED (推荐使用)
           → DEPRECATED (废弃)
           → SENSITIVE (敏感)
           → BLOCKED (阻断)

NORMAL ↔ RECOMMENDED (升降级)
NORMAL/RECOMMENDED → DEPRECATED/SENSITIVE/BLOCKED
DEPRECATED/BLOCKED → NORMAL (恢复，需审核)
```

## Quality Score Calculation

```
total_score = 0.30 * completeness_score
            + 0.25 * accuracy_score
            + 0.25 * consistency_score
            + 0.10 * timeliness_score
            + 0.10 * traceability_score

dimension_score = max(0, 100 - sum(deduction_points_of_issues_in_dimension))
```

存储位置：metadata_snapshot.quality_score
