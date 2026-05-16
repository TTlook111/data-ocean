# Data Model: 元数据版本与审核模块

**Date**: 2026-05-16

## Tables

### snapshot_audit_log（快照操作审计日志）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| snapshot_id | BIGINT | NO | | 关联快照 |
| datasource_id | BIGINT | NO | | 数据源 |
| action | VARCHAR(30) | NO | | 操作类型 |
| old_status | VARCHAR(20) | YES | NULL | 变更前状态 |
| new_status | VARCHAR(20) | YES | NULL | 变更后状态 |
| operator_id | BIGINT | NO | | 操作人 |
| reason | VARCHAR(500) | YES | NULL | 操作原因/备注 |
| context_json | JSON | YES | NULL | 操作上下文（附加信息） |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | 操作时间 |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_snapshot` (snapshot_id, created_at DESC)
- INDEX `idx_ds` (datasource_id, created_at DESC)
- INDEX `idx_operator` (operator_id, created_at DESC)

**action 枚举值**:
- STATUS_TRANSITION: 状态流转
- PUBLISH: 发布
- EXPIRE: 过期（被新版本替代）
- REVOKE: 紧急撤回
- QUALITY_CHECK_START: 开始质量校验
- QUALITY_CHECK_COMPLETE: 质量校验完成
- AUTO_CLEANUP: 自动清理

## Extended Fields on metadata_snapshot (003模块表)

本模块不新建快照表，而是扩展使用 003 模块的 `metadata_snapshot` 表。以下字段已在 003 的 data-model 中定义：

| Column | Usage in This Module |
|--------|---------------------|
| status | 状态流转核心字段 |
| reviewed_by | 审核人（APPROVED 时填写） |
| reviewed_at | 审核时间 |
| published_at | 发布时间（PUBLISHED 时填写） |
| expired_at | 过期时间（EXPIRED 时填写） |
| quality_score | 质量分（CHECKING 完成后由 004 模块填写） |

## Relationships

```
metadata_snapshot 1 ──── N snapshot_audit_log
```

**跨模块依赖**:
- metadata_snapshot (003模块) — 本模块操作的核心实体
- metadata_quality_issue (004模块) — 发布前检查是否有未解决 HIGH 问题
- db_table_meta.governance_status (003模块) — 发布前检查是否有可用表

## State Transitions

### metadata_snapshot.status (完整生命周期)

```
                    ┌─────────────────────────────────┐
                    │                                 │
                    ▼                                 │
DRAFT ──→ CHECKING ──→ ISSUE_FOUND ──→ APPROVED ──→ PUBLISHED ──→ EXPIRED
                    │                      ▲              │
                    └──────────────────────┘              │
                    (无问题直接通过)          (紧急撤回)    │
                                            └─────────────┘
```

**Transition Conditions**:

| From | To | Condition | Trigger |
|------|----|-----------|---------|
| DRAFT | CHECKING | 无 | 管理员触发质量校验 |
| CHECKING | APPROVED | 无 HIGH/MEDIUM 问题 | 系统自动 |
| CHECKING | ISSUE_FOUND | 存在 HIGH/MEDIUM 问题 | 系统自动 |
| ISSUE_FOUND | APPROVED | 所有 HIGH 问题已处理 | 管理员手动 |
| APPROVED | PUBLISHED | 至少一张表为 NORMAL/RECOMMENDED | 管理员手动 |
| PUBLISHED | EXPIRED | 新快照发布 | 系统自动 |
| PUBLISHED | APPROVED | 紧急撤回 | 管理员手动（需填写原因） |

## Publish Atomicity

发布操作在单个数据库事务内完成：

```sql
BEGIN;
-- 1. 锁定该数据源的快照
SELECT id FROM metadata_snapshot WHERE datasource_id = ? FOR UPDATE;

-- 2. 旧快照过期
UPDATE metadata_snapshot 
SET status = 'EXPIRED', expired_at = NOW() 
WHERE datasource_id = ? AND status = 'PUBLISHED';

-- 3. 新快照发布
UPDATE metadata_snapshot 
SET status = 'PUBLISHED', published_at = NOW(), reviewed_by = ? 
WHERE id = ? AND status = 'APPROVED';

-- 4. 记录审计日志
INSERT INTO snapshot_audit_log (...) VALUES (...);

COMMIT;
```

## Cleanup Policy

| 条件 | 动作 |
|------|------|
| 同一数据源 EXPIRED 快照 > 50 个 | 物理删除最早的 EXPIRED 快照及关联数据 |
| 清理频率 | 每周一次定时任务 |
| 保护规则 | PUBLISHED/APPROVED 永不清理 |
| 级联删除 | 快照删除时级联删除 db_table_meta, db_column_meta, table_relation, schema_change_event |
