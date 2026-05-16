# Feature Specification: SQL 安全与执行沙箱模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: AI 生成的 SQL 不能直接执行，必须经过安全网关校验和沙箱约束。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - SQL 安全校验拦截危险操作 (Priority: P1)

Agent 生成 SQL 后，系统通过 AST 解析校验 SQL 安全性，拦截非 SELECT 语句和危险函数。

**Why this priority**: 安全校验是防止 AI 误操作导致数据损坏的最后防线，是系统安全的基石。

**Independent Test**: 构造一条 DELETE 语句提交给校验器，100% 被拦截。

**Acceptance Scenarios**:

1. **Given** LLM 生成了一条 SELECT 语句, **When** 经过 AST 校验, **Then** 校验通过，允许执行
2. **Given** LLM 生成了一条 DROP TABLE 语句, **When** 经过 AST 校验, **Then** 校验不通过，返回安全告警
3. **Given** SQL 中包含 SLEEP() 函数, **When** 经过 AST 校验, **Then** 校验不通过，拦截危险函数
4. **Given** SQL 中包含 SELECT *, **When** 经过 AST 校验, **Then** 校验不通过，禁止星号查询

---

### User Story 2 - 行列级权限在 AST 层强制执行 (Priority: P1)

系统根据用户权限配置，在 SQL 执行前自动注入行级过滤条件和列级访问控制。

**Why this priority**: 权限不能只靠 Prompt 约束，必须在 AST 层硬性执行。

**Independent Test**: 用户只有"华东区"数据权限时，生成的 SQL 自动追加 WHERE region='华东'。

**Acceptance Scenarios**:

1. **Given** 用户有行级过滤规则 region='华东', **When** SQL 通过校验, **Then** 自动在对应表的 WHERE 中注入过滤条件
2. **Given** SQL 中查询了用户无权限的字段, **When** 经过列级校验, **Then** 拒绝执行并提示"无权访问该字段"
3. **Given** SQL 中查询了敏感字段, **When** 经过校验, **Then** 标记该字段需要脱敏处理

---

### User Story 3 - SQL 沙箱约束执行 (Priority: P1)

通过校验的 SQL 在沙箱环境中执行，强制只读事务、超时控制和结果集限制。

**Why this priority**: 沙箱约束防止 AI 查询影响业务数据库性能。

**Independent Test**: 一条执行超过 30 秒的 SQL 被自动终止。

**Acceptance Scenarios**:

1. **Given** SQL 通过安全校验, **When** 执行时, **Then** 强制使用只读事务
2. **Given** SQL 未指定 LIMIT, **When** 执行前, **Then** 自动注入 LIMIT 10000
3. **Given** SQL 执行超过 30 秒, **When** 超时触发, **Then** 自动终止查询并返回超时提示

---

### Edge Cases

- SQL 中有子查询嵌套超过 3 层？（AST 检测并拒绝）
- 行级过滤注入后 SQL 语法错误？（注入后重新做语法校验）
- 连接池耗尽时新查询如何处理？（排队等待或返回"系统繁忙"）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 使用 sqlglot 进行 AST 解析，仅允许 SELECT 语句
- **FR-002**: 系统 MUST 拦截危险函数（SLEEP、BENCHMARK、LOAD_FILE、INTO OUTFILE 等）
- **FR-003**: 系统 MUST 限制子查询嵌套深度最大 3 层
- **FR-004**: 系统 MUST 禁止 SELECT * 查询
- **FR-005**: 系统 MUST 校验表访问白名单（仅允许当前数据源已启用的表）
- **FR-006**: 系统 MUST 在 AST 层注入行级过滤条件
- **FR-007**: 系统 MUST 在 AST 层校验列级访问权限
- **FR-008**: 系统 MUST 强制只读事务执行
- **FR-009**: 系统 MUST 强制注入 LIMIT 10000（用户未指定时）
- **FR-010**: 系统 MUST 设置单条 SQL 最大执行时间 30 秒
- **FR-011**: 系统 MUST 使用独立连接池，单数据源最大 10 连接，全局上限 50

### Key Entities

- **SQLValidationResult**: 校验结果，包含是否通过、拒绝原因、检测到的问题列表
- **ExecutionConstraint**: 执行约束配置，包含超时时间、最大行数、最大嵌套深度
- **ConnectionPool**: 连接池，以 datasource_id 为 key，包含最大连接数、空闲超时

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 非 SELECT 语句拦截率 100%
- **SC-002**: 危险函数拦截率 100%
- **SC-003**: 行列级权限绕过率 0%
- **SC-004**: 超时查询 100% 被自动终止

## Assumptions

- SQL 校验使用 Python sqlglot 库，MySQL 方言
- 连接池使用 Python 异步连接池（如 aiomysql）
- 权限信息由 Java 每次请求传入，Python 不缓存权限数据
