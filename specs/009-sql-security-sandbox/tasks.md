# Tasks: SQL 安全与执行沙箱模块

**Input**: Design documents from `specs/009-sql-security-sandbox/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Python 包结构 `python-service/dataocean/sandbox/`，包含 `__init__.py`、`router.py`、`validator.py`、`rewriter.py`、`executor.py`、`pool_manager.py`、`schema.py`、`config.py` 和 `rules/` 子目录（含 `__init__.py`）
- [ ] T002 实现安全配置 `python-service/dataocean/sandbox/config.py`，从环境变量读取：MAX_EXECUTION_TIME（默认 30s）、MAX_RESULT_ROWS（默认 10000）、MAX_SUBQUERY_DEPTH（默认 3）、POOL_MAX_PER_SOURCE（默认 10）、POOL_MAX_GLOBAL（默认 50）、POOL_IDLE_TIMEOUT（默认 1800s）、POOL_WAIT_TIMEOUT（默认 5s）、AES_SECRET_KEY

## Phase 2: Foundational — AST 校验引擎

- [ ] T003 [P] 实现 `python-service/dataocean/sandbox/rules/statement_rule.py`：使用 sqlglot.parse 解析 SQL（dialect="mysql"），检查 AST 根节点类型是否为 exp.Select，非 SELECT 语句返回 RuleResult(passed=False, reason="仅允许 SELECT 语句")
- [ ] T004 [P] 实现 `python-service/dataocean/sandbox/rules/function_rule.py`：遍历 AST 中所有 exp.Anonymous 和 exp.Func 节点，检查函数名是否在黑名单中（SLEEP、BENCHMARK、LOAD_FILE、INTO_OUTFILE、OUTFILE、DUMPFILE、SYSTEM、EXEC），命中则返回失败
- [ ] T005 [P] 实现 `python-service/dataocean/sandbox/rules/depth_rule.py`：递归遍历 AST 中的 exp.Subquery 节点，计算最大嵌套深度，超过 MAX_SUBQUERY_DEPTH 则返回失败
- [ ] T006 [P] 实现 `python-service/dataocean/sandbox/rules/star_rule.py`：检查 SELECT 列表中是否包含 exp.Star 节点，包含则返回 RuleResult(passed=False, reason="禁止 SELECT *，请指定具体字段")
- [ ] T007 [P] 实现 `python-service/dataocean/sandbox/rules/table_rule.py`：提取 AST 中所有 exp.Table 节点的表名，与传入的 allowed_tables 白名单对比，存在未授权表则返回失败并列出具体表名
- [ ] T008 [P] 实现 `python-service/dataocean/sandbox/rules/limit_rule.py`：检查 AST 中是否存在 exp.Limit 节点，若存在且值 > MAX_RESULT_ROWS 则返回失败
- [ ] T009 实现 `python-service/dataocean/sandbox/validator.py`：校验引擎主逻辑，按顺序执行规则链（statement → function → depth → star → table → limit），聚合所有 RuleResult，任一失败则整体不通过；返回 ValidationResult（passed、issues: List[RuleResult]）

## Phase 3: User Story 2 (P1) — AST 改写（行列级权限）

**Goal**: 行列级权限在 AST 层强制执行
**Independent Test**: 用户只有"华东区"数据权限时，生成的 SQL 自动追加 WHERE region='华东'

- [ ] T010 [US2] 实现 `python-service/dataocean/sandbox/rewriter.py` 中 inject_row_filter 方法：接收 row_filters（Dict[table_name, List[condition_expr]]），解析 AST 找到对应表的 FROM/JOIN 子句，在 WHERE 中追加 AND 条件（使用 sqlglot.exp.And 构建）；若原 SQL 无 WHERE 则创建新的 WHERE 子句
- [ ] T011 [US2] 实现 rewriter.py 中 check_column_access 方法：接收 denied_columns（Dict[table_name, List[column_name]]），遍历 SELECT 列表和 WHERE 中的列引用，发现无权限列则返回拒绝结果
- [ ] T012 [US2] 实现 rewriter.py 中 mark_sensitive_columns 方法：接收 mask_columns（Dict[table_name, List[column_name]]），在返回结果中标记需要脱敏的字段列表（不改写 SQL，由执行层处理脱敏）
- [ ] T013 [US2] 实现 rewriter.py 中 inject_limit 方法：检查 AST 是否有 LIMIT 节点，无则追加 LIMIT {MAX_RESULT_ROWS}；有但值超过上限则替换为上限值
- [ ] T014 [US2] 在 rewriter.py 中实现 rewrite 主方法：按顺序调用 inject_row_filter → check_column_access → mark_sensitive_columns → inject_limit → 将改写后的 AST 通过 sqlglot.generate 输出为 SQL 字符串 → 对输出 SQL 重新 parse 验证语法正确性

## Phase 4: User Story 3 (P1) — 连接池管理

**Goal**: 管理业务数据库只读连接
**Independent Test**: 连接池耗尽时返回"系统繁忙"而非无限等待

- [ ] T015 [US3] 实现 `python-service/dataocean/sandbox/pool_manager.py`：维护 Dict[int, AsyncEngine] 按 datasource_id 管理连接池，提供 get_engine(datasource_id, connection_config) 方法——首次调用时创建 AsyncEngine（SQLAlchemy create_async_engine + aiomysql），配置 pool_size=POOL_MAX_PER_SOURCE、pool_timeout=POOL_WAIT_TIMEOUT
- [ ] T016 [US3] 在 pool_manager.py 中实现密码解密：connection_config 中的 password 为 AES-256-CBC 加密，创建连接池前使用 AES_SECRET_KEY 解密
- [ ] T017 [US3] 在 pool_manager.py 中实现全局连接数限制：维护全局计数器，创建新池前检查所有池的 pool_size 总和是否超过 POOL_MAX_GLOBAL，超过则拒绝创建并返回错误
- [ ] T018 [US3] 在 pool_manager.py 中实现空闲回收：记录每个池的最后使用时间，提供 cleanup_idle_pools 方法（定期调用），关闭超过 POOL_IDLE_TIMEOUT 未使用的连接池

## Phase 5: User Story 3 (P1) — 沙箱执行

**Goal**: SQL 沙箱约束执行
**Independent Test**: 一条执行超过 30 秒的 SQL 被自动终止

- [ ] T019 [US3] 实现 `python-service/dataocean/sandbox/executor.py`：execute 方法接收 validated_sql、datasource_id、connection_config、mask_columns，从 pool_manager 获取 engine → 开启只读事务（SET TRANSACTION READ ONLY）→ 设置超时（SET max_execution_time={MAX_EXECUTION_TIME*1000}）→ 执行 SQL → 返回 ExecutionResult（rows、columns、execution_time_ms、row_count）
- [ ] T020 [US3] 在 executor.py 中实现超时自动终止：使用 asyncio.wait_for 包裹 SQL 执行，超时后获取 connection_id 并执行 KILL QUERY {id}，返回 ExecutionResult(success=False, error="查询超时，已自动终止")
- [ ] T021 [US3] 在 executor.py 中实现敏感字段标记：执行完成后，根据 mask_columns 在 ExecutionResult 中返回 maskedFields 列表（不修改结果数据，脱敏由 Java 网关层统一处理）
- [ ] T022 [US3] 在 executor.py 中实现连接池繁忙处理：get_engine 超时（pool_timeout）时捕获异常，返回 ExecutionResult(success=False, error="系统繁忙，请稍后重试")

## Phase 6: API 路由

- [ ] T023 实现 Pydantic 模型 `python-service/dataocean/sandbox/schema.py`：ValidateRequest（sql、datasource_id、allowed_tables、row_filters、denied_columns、mask_columns）、ValidateResponse（passed、issues、rewritten_sql、masked_fields）、ExecuteRequest（sql、datasource_id、connection_config、mask_columns）、ExecuteResponse（success、rows、columns、execution_time_ms、row_count、error）
- [ ] T024 实现路由 `python-service/dataocean/sandbox/router.py`：POST /internal/sql/validate（校验+改写，返回 ValidateResponse）、POST /internal/sql/execute（沙箱执行，返回 ExecuteResponse）

## Phase 7: Polish & Cross-Cutting

- [ ] T025 在 validator.py 中添加 SQL 注入二次防护：校验 SQL 中不包含注释符号（--、/**/）和多语句分隔符（;），作为 Prompt 注入的兜底防线
- [ ] T026 在 executor.py 中添加结果集大小检查：row_count 超过 MAX_RESULT_ROWS 时截断并在响应中标记 truncated=true
- [ ] T027 在 router.py 中添加 GET /internal/sql/health 健康检查接口，返回连接池状态（活跃池数量、总连接数、各池使用率）

## Phase 8: Pool Management API

- [ ] T028 在 router.py 中添加 GET /internal/sql/pools/dashboard 接口：返回所有活跃连接池的详细状态（datasource_id、active_connections、idle_connections、pool_size、last_used_at、created_at）
- [ ] T029 在 router.py 中添加 POST /internal/sql/pools/{datasourceId}/reset 接口：强制销毁并重建指定数据源的连接池
- [ ] T030 [Frontend] 在系统设置或数据源详情页添加"连接池状态"面板，展示各数据源的连接池使用率

## Dependencies

```
T001 → T002~T009
T003~T008 → T009 (规则链依赖各规则实现)
T009 → T010~T014 (改写依赖校验)
T002 → T015~T018 (连接池依赖配置)
T015~T018 → T019~T022 (执行依赖连接池)
T009 + T014 + T019 → T023~T024 (路由依赖校验+改写+执行)
```

## Implementation Strategy

MVP-first approach:
1. 先实现 AST 校验引擎（Phase 2），这是安全基石，用大量 SQL 用例验证
2. 再实现 AST 改写（Phase 3），确保行列级权限硬性执行
3. 然后搭建连接池（Phase 4）和沙箱执行（Phase 5）
4. 最后暴露 API（Phase 6），供 008 Agent 模块调用
5. 安全加固（Phase 7）贯穿始终，每个阶段都要考虑边界情况
