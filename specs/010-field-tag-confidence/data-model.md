# Data Model: 字段 Tag 与可信度模块

## Entity Relationship

```
column_meta (1) ──< field_tag (N)
column_meta (1) ──< field_confidence (1)
field_confidence (1) ──< field_confidence_event (N)
query_task (1) ──< user_feedback (N)
user_feedback (1) ──< feedback_review (1)
```

## Tables

### field_tag

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 标签ID |
| column_meta_id | BIGINT | FK → column_meta.id, NOT NULL | 关联字段 |
| tag_code | VARCHAR(50) | NOT NULL | 标签编码 (AMOUNT/TIME/STATUS/USER_ID/SENSITIVE/DEPRECATED/BLOCKED) |
| tag_name | VARCHAR(50) | NOT NULL | 标签显示名 |
| source | VARCHAR(20) | NOT NULL, DEFAULT 'MANUAL' | 来源: MANUAL/AUTO/IMPORT |
| created_by | BIGINT | FK → sys_user.id | 创建人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

UNIQUE INDEX: (column_meta_id, tag_code)

### field_confidence

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 可信度ID |
| column_meta_id | BIGINT | FK → column_meta.id, UNIQUE, NOT NULL | 关联字段 |
| score | INT | NOT NULL, DEFAULT 0, CHECK(0-100) | 当前可信度分数 |
| level | VARCHAR(10) | NOT NULL, DEFAULT 'LOW' | HIGH(>=80)/MEDIUM(40-79)/LOW(<40) |
| source | VARCHAR(20) | NOT NULL | 最近一次更新来源: SCHEMA/SKILLS/MANUAL/ADMIN/FEEDBACK |
| reason | VARCHAR(200) | | 最近一次变更原因 |
| version | INT | NOT NULL, DEFAULT 0 | 乐观锁版本号 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 最后更新时间 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

### field_confidence_event

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 事件ID |
| column_meta_id | BIGINT | FK → column_meta.id, NOT NULL | 关联字段 |
| previous_score | INT | NOT NULL | 变更前分数 |
| new_score | INT | NOT NULL | 变更后分数 |
| delta_score | INT | NOT NULL | 变更量 (+/-) |
| event_type | VARCHAR(30) | NOT NULL | SCHEMA_INIT/SKILLS_PUBLISH/MANUAL_CONFIRM/ADMIN_SET/THUMBS_UP/SUCCESSFUL_USE/CONFIRMED_DOWN/GROUP_THRESHOLD |
| source_query_id | BIGINT | NULLABLE | 关联查询任务ID |
| source_feedback_id | BIGINT | NULLABLE | 关联反馈ID |
| operator_id | BIGINT | NULLABLE | 操作人ID |
| remark | VARCHAR(200) | | 备注 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 事件时间 |

INDEX: (column_meta_id, created_at)

### user_feedback

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 反馈ID |
| query_task_id | BIGINT | NOT NULL | 关联查询任务 |
| user_id | BIGINT | FK → sys_user.id, NOT NULL | 反馈用户 |
| feedback_type | VARCHAR(10) | NOT NULL | THUMBS_UP / THUMBS_DOWN |
| reason_code | VARCHAR(30) | NULLABLE | 踩的原因: DATA_INACCURATE/WRONG_TABLE/WRONG_FIELD/OTHER |
| comment | VARCHAR(500) | NULLABLE | 补充说明 |
| related_column_ids | JSON | NULLABLE | 关联字段ID列表 |
| review_status | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/APPROVED/REJECTED (仅踩需审核) |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 反馈时间 |

INDEX: (user_id, created_at)
INDEX: (review_status, created_at)

### feedback_review

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 审核ID |
| feedback_id | BIGINT | FK → user_feedback.id, UNIQUE, NOT NULL | 关联反馈 |
| review_status | VARCHAR(20) | NOT NULL | APPROVED / REJECTED |
| reviewer_id | BIGINT | FK → sys_user.id, NOT NULL | 审核人 |
| review_comment | VARCHAR(500) | NULLABLE | 审核意见 |
| confidence_applied | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否已应用可信度变更 |
| handled_at | DATETIME | NOT NULL, DEFAULT NOW() | 审核时间 |

## State Transitions

### Feedback Review Status

```
PENDING ──[管理员通过]──> APPROVED → 触发可信度 -15
PENDING ──[管理员驳回]──> REJECTED → 可信度不变
```

### Confidence Level (derived from score)

```
score >= 80  → HIGH
40 <= score < 80 → MEDIUM
score < 40  → LOW
```

## Validation Rules

- tag_code: 必须是预定义标签或已注册的自定义标签
- score: [0, 100] 整数，超出边界自动截断
- feedback_type: 仅 THUMBS_UP 或 THUMBS_DOWN
- reason_code: 踩时必填，赞时为空
- 同一用户同一字段每天最多 1 次 THUMBS_DOWN（Redis 限频）
