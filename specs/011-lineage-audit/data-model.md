# Data Model: 血缘与审计模块

## Entity Relationship

```
query_task (1) ──< query_audit_log (1)
query_audit_log (1) ──< query_lineage_table (N)
query_audit_log (1) ──< query_lineage_column (N)
query_task (1) ──< llm_usage_log (N)
quota_policy ── standalone (per user/dept/datasource)
```

## Tables

### query_audit_log

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 审计日志ID |
| query_task_id | BIGINT | NOT NULL, UNIQUE | 关联查询任务 |
| user_id | BIGINT | NOT NULL | 查询用户 |
| datasource_id | BIGINT | NOT NULL | 数据源ID |
| question | TEXT | NOT NULL | 用户原始问题 |
| generated_sql | TEXT | NULLABLE | 生成的 SQL |
| execution_time_ms | INT | NULLABLE | SQL 执行耗时(ms) |
| row_count | INT | NULLABLE | 返回行数 |
| is_success | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否成功 |
| error_message | VARCHAR(500) | NULLABLE | 错误信息 |
| is_slow | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否慢查询 |
| feedback_type | VARCHAR(10) | NULLABLE | 用户反馈: THUMBS_UP/THUMBS_DOWN |
| used_tables | JSON | NULLABLE | 使用的表列表 |
| used_columns | JSON | NULLABLE | 使用的字段列表 |
| session_id | VARCHAR(64) | NULLABLE | 会话ID |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 记录时间 |

INDEX: (user_id, created_at)
INDEX: (datasource_id, created_at)
INDEX: (is_slow, created_at)
INDEX: (created_at) — 用于清理任务

### query_lineage_table

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 血缘ID |
| audit_log_id | BIGINT | FK → query_audit_log.id, NOT NULL | 关联审计日志 |
| table_meta_id | BIGINT | NULLABLE | 关联元数据表ID（可能未注册） |
| schema_name | VARCHAR(100) | NOT NULL | 库名 |
| table_name | VARCHAR(100) | NOT NULL | 表名 |
| relation_type | VARCHAR(20) | NOT NULL | FROM/JOIN/SUBQUERY |
| alias_name | VARCHAR(100) | NULLABLE | 表别名 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 记录时间 |

INDEX: (table_meta_id)
INDEX: (table_name)

### query_lineage_column

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 血缘ID |
| audit_log_id | BIGINT | FK → query_audit_log.id, NOT NULL | 关联审计日志 |
| column_meta_id | BIGINT | NULLABLE | 关联元数据字段ID |
| source_table | VARCHAR(100) | NOT NULL | 来源表名 |
| source_column | VARCHAR(100) | NOT NULL | 来源字段名 |
| expression | VARCHAR(500) | NULLABLE | 表达式（如 SUM(amount)） |
| alias_name | VARCHAR(100) | NULLABLE | 输出别名 |
| is_aggregated | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否聚合 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 记录时间 |

INDEX: (column_meta_id)
INDEX: (source_table, source_column)

### llm_usage_log

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 日志ID |
| query_task_id | BIGINT | NOT NULL | 关联查询任务 |
| user_id | BIGINT | NOT NULL | 用户ID |
| provider | VARCHAR(30) | NOT NULL | 提供商: QWEN/DASHSCOPE |
| model | VARCHAR(50) | NOT NULL | 模型名: qwen-plus/qwen-turbo |
| prompt_tokens | INT | NOT NULL | 输入 token 数 |
| completion_tokens | INT | NOT NULL | 输出 token 数 |
| total_tokens | INT | NOT NULL | 总 token 数 |
| cost_amount | DECIMAL(10,6) | NOT NULL | 费用(元) |
| latency_ms | INT | NULLABLE | 调用耗时 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 调用时间 |

INDEX: (user_id, created_at)
INDEX: (created_at)

### quota_policy

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 策略ID |
| subject_type | VARCHAR(20) | NOT NULL | USER/DEPARTMENT/DATASOURCE/GLOBAL |
| subject_id | BIGINT | NULLABLE | 主体ID（GLOBAL 时为空） |
| daily_query_limit | INT | NOT NULL, DEFAULT 100 | 每日查询次数上限 |
| monthly_cost_limit | DECIMAL(10,2) | NOT NULL, DEFAULT 100.00 | 月度成本上限(元) |
| enabled | BOOLEAN | NOT NULL, DEFAULT TRUE | 是否启用 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |

UNIQUE INDEX: (subject_type, subject_id)

## Validation Rules

- question: 不能为空，最大 2000 字符
- generated_sql: 成功查询时必填
- execution_time_ms: >= 0
- row_count: >= 0
- is_slow: 由系统根据 execution_time_ms > threshold 自动设置
- daily_query_limit: > 0
- monthly_cost_limit: > 0
