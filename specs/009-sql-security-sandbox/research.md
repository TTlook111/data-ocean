# Research: SQL 安全与执行沙箱模块

## SQL 解析库

**Decision**: sqlglot (Python)

**Rationale**: 纯 Python 实现，无外部依赖，支持 MySQL 方言，AST 操作 API 丰富（解析、遍历、改写、生成），性能满足需求（单条 SQL 解析 < 5ms）。

**Alternatives considered**:
- sqlparse: 只做 tokenize，不提供完整 AST，无法做深度分析
- antlr4 + MySQL grammar: 过重，需要维护 grammar 文件
- mysql-connector-python 内置 parser: 功能有限

## 连接池方案

**Decision**: SQLAlchemy 2.x AsyncEngine + create_async_engine (aiomysql driver)

**Rationale**: SQLAlchemy 2.x 原生支持 async，连接池管理成熟（pool_size, max_overflow, pool_timeout, pool_recycle）。与项目其他 Python 模块技术栈一致。

**配置**:
```python
create_async_engine(
    url,
    pool_size=10,          # 单源最大连接
    max_overflow=0,        # 不允许溢出
    pool_timeout=5,        # 等待连接超时
    pool_recycle=1800,     # 30min 回收空闲连接
    pool_pre_ping=True,    # 使用前检查连接有效性
)
```

**全局限制**: 自定义 PoolManager 维护所有 engine，创建新 engine 前检查全局连接总数 <= 50。

## 超时控制方案

**Decision**: MySQL max_execution_time hint + asyncio.wait_for 双重保障

**实现**:
1. SQL 前追加 `/*+ MAX_EXECUTION_TIME(30000) */` hint (MySQL 5.7.8+)
2. Python 侧 `asyncio.wait_for(execute(), timeout=32)` 兜底 (多 2s 容错)
3. 超时后执行 `KILL QUERY {connection_id}` 确保 MySQL 侧也终止

**Rationale**: MySQL hint 是数据库级保障，asyncio 是应用级保障，双重防护。

## 只读事务方案

**Decision**: 连接级 READ ONLY + 账号级权限

**实现**:
1. 每次执行前: `SET TRANSACTION READ ONLY`
2. 业务库连接账号仅授予 SELECT 权限 (GRANT SELECT ON db.* TO 'readonly'@'%')
3. 双重保障: 即使 AST 校验遗漏，数据库层也会拒绝写操作

## 黑名单函数

**Decision**: 硬编码黑名单 + 可配置扩展

**黑名单**:
```python
DANGEROUS_FUNCTIONS = {
    # 时间延迟
    "SLEEP", "BENCHMARK",
    # 文件操作
    "LOAD_FILE", "LOAD_DATA",
    # 系统信息
    "USER", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER",
    "VERSION", "DATABASE",
    # 网络
    "MASTER_POS_WAIT",
    # 其他
    "GET_LOCK", "RELEASE_LOCK", "IS_FREE_LOCK",
}

DANGEROUS_CLAUSES = {
    "INTO OUTFILE", "INTO DUMPFILE",
}
```

**Rationale**: 硬编码保证核心安全，配置文件支持运维动态扩展。

## 行级过滤注入方案

**Decision**: sqlglot AST 改写，在目标表的 WHERE 子句追加 AND 条件

**实现**:
1. 解析 SQL AST，找到所有 FROM/JOIN 中的表引用
2. 匹配 rowFilters 中的 tableName
3. 如果表有别名，条件中的表名替换为别名
4. 追加 AND 条件到 WHERE 子句（无 WHERE 则创建）
5. 改写后重新 generate SQL

**边界情况**:
- 子查询中的表: 递归处理
- UNION 查询: 每个 SELECT 分支独立注入
- 注入后语法错误: 重新 parse 验证，失败则拒绝执行

## 列级访问控制方案

**Decision**: AST 遍历 SELECT 列表，对比 deniedColumns 和 maskColumns

**实现**:
1. 遍历 SELECT 列表中的所有列引用
2. 解析 table.column 全限定名（处理别名）
3. deniedColumns 中的列 → 拒绝执行，返回"无权访问字段 xxx"
4. maskColumns 中的列 → 标记脱敏，执行后对结果集做脱敏处理

**脱敏策略**:
- PARTIAL: 保留前后各 2 字符，中间用 * 替代
- FULL: 全部替换为 ****
- HASH: SHA256 后取前 8 位

## 密码管理方案

**Decision**: AES-256-GCM 加密存储，仅创建连接池时解密，解密后不缓存明文

**实现**:
1. 密码在 Java 层加密后存入 datasource 表
2. Python 创建连接池时，从 Java 获取加密密码 + 解密密钥
3. 解密后立即用于创建 engine，不存储明文
4. 密钥通过环境变量 AES_SECRET_KEY 注入

**Rationale**: 最小化密码明文暴露窗口，即使内存 dump 也无法获取密码。
