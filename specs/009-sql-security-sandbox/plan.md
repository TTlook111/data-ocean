# Implementation Plan: SQL 安全与执行沙箱模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

SQL 安全沙箱是系统的最后安全防线，使用 sqlglot 对 AI 生成的 SQL 进行 AST 级安全校验（白名单语句、黑名单函数、嵌套深度、表权限），并在只读沙箱环境中约束执行（超时、LIMIT、只读事务）。同时在 AST 层强制注入行级过滤和列级访问控制。

## Technical Context

**Language/Version**: Python 3.13 (FastAPI)

**Primary Dependencies**:
- sqlglot (SQL AST 解析, MySQL 方言)
- SQLAlchemy 2.x + PyMySQL (连接池管理)
- asyncio (超时控制)

**Storage**: 无自有持久化，连接池连接业务库（只读）

**Testing**: pytest, 大量 SQL 用例覆盖

**Target Platform**: Docker Compose (ai-service container)

**Performance Goals**: 校验 < 50ms, 执行超时 30s

**Constraints**: 仅 SELECT, 只读事务, 单源最大 10 连接, 全局最大 50

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | N/A | 本模块不涉及元数据 |
| II. SQL 安全与只读执行 | ✅ PASS | 核心实现模块 |
| III. 三层分离架构 | ✅ PASS | Python 内部服务 |
| IV. RAG 准入控制 | N/A | 本模块不涉及 RAG |
| V. 可信度驱动生成 | N/A | 本模块不涉及可信度 |
| VI. 渐进式 MVP | ✅ PASS | MVP 实现全部安全规则 |

**Gate Result**: PASS

## Project Structure

```text
ai-service/dataocean/sandbox/
├── __init__.py
├── router.py              # FastAPI 路由
├── validator.py           # SQL AST 校验主逻辑
├── rules/
│   ├── __init__.py
│   ├── statement_rule.py      # 仅允许 SELECT
│   ├── function_rule.py       # 黑名单函数
│   ├── depth_rule.py          # 子查询嵌套深度
│   ├── star_rule.py           # 禁止 SELECT *
│   ├── table_rule.py          # 表白名单
│   └── limit_rule.py          # 强制 LIMIT
├── rewriter.py            # AST 改写 (行过滤注入, LIMIT 注入)
├── executor.py            # SQL 沙箱执行
├── pool_manager.py        # 连接池管理
├── schema.py              # Pydantic 模型
└── config.py              # 安全配置
```

## Implementation Phases

### Phase 1: AST 校验引擎
- sqlglot 解析 SQL (MySQL 方言)
- 规则链: statement_rule → function_rule → depth_rule → star_rule → table_rule
- 每条规则独立，返回 pass/fail + 原因
- 校验结果聚合

### Phase 2: AST 改写
- 行级过滤注入: 解析 WHERE 子句，追加 AND 条件
- 列级访问检查: 遍历 SELECT 列，对比 deniedColumns
- 敏感字段标记: maskColumns 标记需脱敏字段
- 强制 LIMIT 注入: 无 LIMIT 时追加 LIMIT 10000
- 改写后重新校验语法正确性

### Phase 3: 连接池管理
- 按 datasource_id 维护连接池 (dict[int, AsyncEngine])
- 单源最大 10 连接, 全局最大 50
- 密码 AES-256 解密 (仅创建池时)
- 空闲超时 30min 回收
- 池满时排队等待 (timeout 5s) 或返回繁忙

### Phase 4: 沙箱执行
- 强制 READ ONLY 事务 (SET TRANSACTION READ ONLY)
- 查询超时 30s (SET max_execution_time=30000)
- 结果集限制 10000 行
- 超时自动 KILL QUERY
- 返回数据 + 列元信息

## Complexity Tracking

无违规项。
