# Data Model: NL2SQL Agent 模块

## 内存状态模型 (LangGraph State)

Agent 状态仅存在于单次请求生命周期内，不持久化。

### AgentState

```python
class AgentState(TypedDict):
    # 输入
    task_id: str                          # 任务ID (Java 生成)
    question: str                         # 用户自然语言问题
    datasource_id: int                    # 数据源ID
    user_id: int                          # 用户ID
    conversation_history: list[dict]      # 最近 5 轮对话 [{role, content}]
    user_permissions: UserPermissions     # 用户权限信息

    # RAG 召回
    schema_context: list[RetrievedContext]  # 召回的表和字段上下文

    # SQL 生成
    generated_sql: str                    # 生成的 SQL
    sql_explanation: str                  # SQL 口径说明

    # 校验
    validation_result: ValidationResult   # 校验结果

    # 执行
    execution_result: ExecutionResult     # 执行结果
    used_tables: list[str]               # 使用的表
    used_columns: list[str]              # 使用的字段

    # 可视化
    chart_config: dict                   # ECharts option JSON

    # 控制
    current_node: str                    # 当前执行节点
    retry_count: int                     # 重试次数
    errors: list[str]                    # 错误历史
    start_time: float                    # 开始时间戳
    cancelled: bool                      # 是否已取消
```

---

## 请求/响应模型

### QueryExecuteRequest (Java → Python)

```python
class QueryExecuteRequest(BaseModel):
    task_id: str
    datasource_id: int
    user_id: int
    question: str
    conversation_history: list[ConversationTurn] = []
    user_permissions: UserPermissions
    active_snapshot_id: int

class ConversationTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str

class UserPermissions(BaseModel):
    row_filters: list[RowFilter] = []
    denied_columns: list[str] = []
    mask_columns: list[MaskColumn] = []
    allowed_tables: list[str]           # 白名单表

class RowFilter(BaseModel):
    table_name: str
    condition: str                      # e.g. "region = '华东'"

class MaskColumn(BaseModel):
    table_name: str
    column_name: str
    mask_type: str                      # PARTIAL / FULL / HASH
```

### QueryResult (SSE event: result)

```python
class QueryResult(BaseModel):
    task_id: str
    status: Literal["COMPLETED", "FAILED", "CANCELLED"]
    sql: str | None = None
    sql_explanation: str | None = None
    data: list[dict] | None = None
    columns: list[ColumnMeta] | None = None
    row_count: int = 0
    chart_config: dict | None = None
    used_tables: list[str] = []
    used_columns: list[str] = []
    retry_count: int = 0
    total_time_ms: int = 0
    error: str | None = None

class ColumnMeta(BaseModel):
    name: str
    type: str
    comment: str | None = None
```

### SSE Progress Event

```python
class ProgressEvent(BaseModel):
    task_id: str
    node: str                           # SCHEMA_RETRIEVER / SQL_GENERATOR / SQL_VALIDATOR / SQL_EXECUTOR / DATA_VISUALIZER
    status: Literal["started", "completed", "failed", "retrying"]
    message: str                        # 人类可读进度描述
    retry_count: int = 0
    elapsed_ms: int = 0
```

---

## MySQL 表 (Java 管理库)

### query_task

由 Java 管理，记录查询任务状态。

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 任务ID |
| task_id | VARCHAR(50) | UNIQUE, NOT NULL | 任务唯一标识 (UUID) |
| user_id | BIGINT | FK → sys_user.id, NOT NULL | 提交用户 |
| datasource_id | BIGINT | FK, NOT NULL | 数据源 |
| question | TEXT | NOT NULL | 用户问题 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'PROCESSING' | PROCESSING/COMPLETED/FAILED/CANCELLED/TIMEOUT |
| result_sql | TEXT | | 最终 SQL |
| result_data | JSON | | 查询结果 (前 100 行) |
| chart_config | JSON | | ECharts 配置 |
| error_message | TEXT | | 错误信息 |
| retry_count | INT | DEFAULT 0 | 重试次数 |
| total_time_ms | INT | | 总耗时 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| completed_at | DATETIME | | 完成时间 |

INDEX: (user_id, created_at DESC)
INDEX: (status)

---

## LangGraph 图结构

```
START
  │
  ▼
Schema_Retriever ──> SQL_Generator ──> SQL_Validator
                                            │
                              ┌──────────────┼──────────────┐
                              │              │              │
                          [PASS]        [REJECT]      [DANGEROUS]
                              │              │              │
                              ▼              ▼              ▼
                        SQL_Executor    SQL_Generator   END (安全告警)
                              │          (retry)
                    ┌─────────┼─────────┐
                    │         │         │
                [SUCCESS]  [FAIL]   [FAIL, retry>=3]
                    │         │         │
                    ▼         ▼         ▼
            Data_Visualizer  SQL_Generator  END (失败)
                    │
                    ▼
                  END (成功)
```
