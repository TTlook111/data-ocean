# Feature Specification: NL2SQL Agent 模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: NL2SQL Agent 负责把用户自然语言转成安全可执行的 SQL，是系统的核心智能链路。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用户自然语言提问并获得查询结果 (Priority: P1)

用户输入自然语言问题（如"华东区上月退款金额"），系统经过 RAG 召回、SQL 生成、安全校验、执行，返回数据结果和图表。

**Why this priority**: 这是整个系统的核心价值，用户使用系统的唯一目的就是通过自然语言获取数据。

**Independent Test**: 输入一个明确的业务问题，系统返回正确的数据表格和图表。

**Acceptance Scenarios**:

1. **Given** 用户已选择数据源且 RAG 知识已就绪, **When** 用户输入"上月订单总额", **Then** 系统生成正确 SQL、执行成功、返回数据和图表
2. **Given** SQL 执行报错, **When** 重试次数 < 3, **Then** Agent 携带错误信息重新生成 SQL 并重试
3. **Given** 重试 3 次仍失败, **When** Agent 放弃重试, **Then** 返回友好错误提示给用户

---

### User Story 2 - SQL 生成优先使用高可信字段 (Priority: P1)

Agent 在生成 SQL 时，根据字段可信度优先选择高可信字段，避免使用废弃字段。

**Why this priority**: 可信度驱动是系统区别于普通 NL2SQL 工具的核心差异化能力。

**Independent Test**: 当存在同义的高可信字段和低可信字段时，生成的 SQL 使用高可信字段。

**Acceptance Scenarios**:

1. **Given** pay_amount(95分) 和 old_amount(20分,已废弃) 都能表达"金额", **When** 用户问"订单金额", **Then** SQL 使用 pay_amount 而非 old_amount
2. **Given** 字段可信度信息注入 Prompt, **When** LLM 生成 SQL, **Then** 生成的 SQL 中不包含任何废弃字段

---

### User Story 3 - 查询进度实时展示 (Priority: P2)

用户提交问题后，前端通过 SSE 实时展示 Agent 各节点的执行进度。

**Why this priority**: 进度展示提升用户体验，但不影响核心查询功能。

**Independent Test**: 用户能看到"正在召回相关表 → 正在生成 SQL → 正在执行 → 正在生成图表"的进度。

**Acceptance Scenarios**:

1. **Given** 用户提交问题, **When** Agent 开始执行, **Then** 前端通过 SSE 实时显示当前节点名称
2. **Given** Agent 正在重试, **When** 前端展示进度, **Then** 显示"SQL 执行失败，正在重试（第 2 次）"

---

### Edge Cases

- 用户问题完全无法理解时？（返回"无法理解您的问题，请换个说法"）
- 召回的表和用户问题完全不相关时？（设置相关度阈值，低于阈值提示"未找到相关数据表"）
- 用户取消查询时？（通过 CancellationToken 终止当前节点执行）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 使用 LangGraph 编排 Agent 工作流（Query_Rewriter → Schema_Retriever → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer）
- **FR-002**: 系统 MUST 在 SQL 执行失败时自动重试，最多 3 次
- **FR-003**: 系统 MUST 将字段可信度信息注入 SQL 生成 Prompt
- **FR-004**: 系统 MUST 支持用户取消正在执行的查询
- **FR-005**: 系统 MUST 通过 SSE 实时推送 Agent 执行进度
- **FR-006**: 系统 MUST 在 SQL_Validator 校验不通过时直接返回安全告警，不进入重试
- **FR-007**: 系统 MUST 设置总时间预算 100 秒，超时停止重试
- **FR-008**: 系统 MUST 返回结果中包含使用的表、字段、SQL、图表配置
- **FR-009**: Query_Rewriter MUST 解析时间表达式（"上个月"→具体日期范围）、消解多轮指代、提取意图（维度/指标/筛选/排序）
- **FR-010**: Query_Rewriter MUST 将改写后的结构化查询意图传递给 Schema_Retriever，提升召回精度
- **FR-011**: Java 网关 MUST 持久化会话消息到 conversation/message 表，页面刷新后可恢复上下文

### Key Entities

- **AgentState**: Agent 状态，包含问题、数据源ID、召回的 Schema、生成的 SQL、错误信息、重试次数、当前节点
- **QueryTask**: 查询任务，包含任务ID、用户ID、数据源ID、问题、状态（PROCESSING/COMPLETED/FAILED/CANCELLED）、结果
- **QueryResult**: 查询结果，包含 SQL、数据行、图表配置、使用的表和字段、口径说明

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 简单查询（单表、无复杂条件）首次生成正确率达到 85%
- **SC-002**: 含重试的整体查询成功率达到 90%
- **SC-003**: 单次查询端到端响应时间 P95 不超过 30 秒
- **SC-004**: 废弃字段在生成的 SQL 中出现率为 0%

## Assumptions

- Python AI 服务请求级无状态，上下文由 Java 每次传入
- LangGraph State 作用域仅限单次请求内部
- 历史对话上下文由前端维护，每次请求透传最近 5 轮
- LLM 使用 Qwen API，具体模型通过配置注入（QWEN_MODEL 环境变量）
- Python 数据库访问使用 SQLAlchemy 2.x + PyMySQL
- 查询提交为异步模式：Java 返回 taskId（HTTP 202），Python 通过 SSE 推送进度事件
- 查询结果不缓存（ADR 决策）
- 阶段一验收标准：20 个预设问题中至少 16 个成功执行，首次失败后 60% 能在 3 次重试内修复

## Clarifications

### Session 2026-05-16

- Q: Python 数据库访问方案？ → A: SQLAlchemy 2.x + PyMySQL（文档第 25 节）
- Q: 查询结果是否缓存？ → A: 不缓存，避免相似问题、相对时间和权限差异导致错误复用（文档第 22 节 ADR）
- Q: 阶段一验收指标？ → A: 高频问题成功率 80%、口径准确率 75%、自我修复成功率 60%（文档第 27.2 节）
