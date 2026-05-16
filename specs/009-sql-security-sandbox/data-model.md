# Data Model: SQL 安全与执行沙箱模块

## 内存模型 (无持久化表)

本模块不拥有数据库表，所有状态为请求级。连接池在内存中管理。

---

## 请求/响应模型

### SQLValidationRequest

```python
class SQLValidationRequest(BaseModel):
    sql: str                            # 待校验的 SQL
    datasource_id: int                  # 数据源ID
    allowed_tables: list[str]           # 表白名单
    user_permissions: UserPermissions   # 用户权限

class UserPermissions(BaseModel):
    row_filters: list[RowFilter] = []
    denied_columns: list[str] = []      # "table.column" 格式
    mask_columns: list[MaskColumn] = []
```

### SQLValidationResult

```python
class SQLValidationResult(BaseModel):
    passed: bool                        # 是否通过
    rewritten_sql: str | None = None    # 改写后的 SQL (注入行过滤/LIMIT)
    violations: list[Violation] = []    # 违规列表
    masked_columns: list[str] = []      # 需脱敏的列

class Violation(BaseModel):
    rule: str                           # STATEMENT/FUNCTION/DEPTH/STAR/TABLE/COLUMN
    severity: str                       # BLOCK (拦截) / WARN (警告)
    message: str                        # 人类可读描述
    detail: str | None = None           # 具体违规内容
```

### SQLExecuteRequest

```python
class SQLExecuteRequest(BaseModel):
    sql: str                            # 已校验+改写后的 SQL
    datasource_id: int                  # 数据源ID
    connection_config: ConnectionConfig # 连接配置
    timeout_seconds: int = 30           # 超时秒数
    max_rows: int = 10000               # 最大返回行数
    masked_columns: list[str] = []      # 需脱敏的列

class ConnectionConfig(BaseModel):
    host: str
    port: int = 3306
    database: str
    username: str
    encrypted_password: str             # AES-256 加密密码
```

### SQLExecuteResult

```python
class SQLExecuteResult(BaseModel):
    success: bool
    data: list[dict] | None = None      # 查询结果
    columns: list[ColumnMeta] | None = None
    row_count: int = 0
    execution_time_ms: int = 0
    error: str | None = None            # 错误信息
    error_type: str | None = None       # SYNTAX/TIMEOUT/CONNECTION/PERMISSION/UNKNOWN

class ColumnMeta(BaseModel):
    name: str
    type: str                           # MySQL 类型
    comment: str | None = None
    masked: bool = False                # 是否已脱敏
```

---

## 连接池管理模型

### PoolManager (内存)

```python
class PoolManager:
    pools: dict[int, AsyncEngine]       # datasource_id → engine
    pool_created_at: dict[int, float]   # 创建时间
    global_connection_count: int        # 全局连接计数

    MAX_PER_SOURCE = 10
    MAX_GLOBAL = 50
    IDLE_TIMEOUT = 1800                 # 30min
```

### 连接池生命周期

```
请求到达 (datasource_id=10)
    │
    ├── 池已存在且健康 → 直接获取连接
    │
    ├── 池不存在 → 检查全局连接数
    │       │
    │       ├── < 50 → 创建新池 (解密密码, 创建 engine)
    │       │
    │       └── >= 50 → 返回 503 "系统繁忙"
    │
    └── 池存在但空闲超时 → 销毁旧池, 创建新池
```

---

## 校验规则链

```
SQL 输入
    │
    ▼
[1] Statement Rule: 仅允许 SELECT
    │ FAIL → BLOCK "仅允许 SELECT 查询"
    ▼
[2] Function Rule: 黑名单函数检测
    │ FAIL → BLOCK "检测到危险函数: SLEEP"
    ▼
[3] Depth Rule: 子查询嵌套 <= 3 层
    │ FAIL → BLOCK "子查询嵌套超过 3 层"
    ▼
[4] Star Rule: 禁止 SELECT *
    │ FAIL → BLOCK "不允许 SELECT *，请指定具体字段"
    ▼
[5] Table Rule: 表白名单校验
    │ FAIL → BLOCK "表 xxx 不在允许范围内"
    ▼
[6] Column Rule: 列访问权限
    │ FAIL → BLOCK "无权访问字段 users.phone"
    ▼
[7] Row Filter Injection: 注入行级过滤
    ▼
[8] Limit Injection: 强制 LIMIT 10000
    ▼
校验通过, 输出 rewritten_sql
```

---

## 脱敏规则

| mask_type | 规则 | 示例 |
|-----------|------|------|
| PARTIAL | 保留前后各 2 字符，中间 * | `13****8000` |
| FULL | 全部替换 | `****` |
| HASH | SHA256 前 8 位 | `a1b2c3d4` |
