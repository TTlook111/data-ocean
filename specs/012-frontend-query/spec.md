# Feature Specification: 前端问答端模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 前端问答端面向普通业务用户，提供自然语言查询、结果展示、图表交互和反馈收集。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用户选择数据源并提问 (Priority: P1)

业务用户打开问答页面，从下拉框选择数据源，输入自然语言问题，等待系统返回结果。

**Why this priority**: 这是用户使用系统的核心入口和主流程。

**Independent Test**: 用户能选择数据源、输入问题、看到查询进度、最终看到结果。

**Acceptance Scenarios**:

1. **Given** 用户打开问答页面, **When** 页面加载完成, **Then** 显示数据源下拉框（仅展示有权限的已启用数据源）和输入框
2. **Given** 用户选择数据源并输入问题, **When** 点击发送, **Then** 显示查询进度（SSE 实时推送）
3. **Given** 查询完成, **When** 结果返回, **Then** 展示数据表格和推荐图表

---

### User Story 2 - 查看结果和图表交互 (Priority: P1)

用户查看查询结果的表格和图表，可以切换图表类型、导出数据。

**Why this priority**: 结果展示是用户获取价值的最终环节。

**Independent Test**: 用户能看到表格、图表，能切换图表类型，能导出 PNG。

**Acceptance Scenarios**:

1. **Given** 查询返回数据和 ECharts 配置, **When** 前端渲染, **Then** 同时展示表格和图表
2. **Given** 用户点击"切换为折线图", **When** 图表重新渲染, **Then** 数据不变，图表类型切换成功
3. **Given** ECharts 配置无效, **When** 前端尝试渲染, **Then** 降级为纯表格展示，不报错

---

### User Story 3 - 多轮对话 (Priority: P2)

用户在同一会话中连续提问，系统理解上下文关联。

**Why this priority**: 多轮对话提升用户体验，但单轮查询已能满足基本需求。

**Independent Test**: 第一轮问"上月订单总额"，第二轮问"按部门拆分"，系统能理解关联。

**Acceptance Scenarios**:

1. **Given** 用户已完成第一轮查询, **When** 输入"按部门拆分看看", **Then** 系统结合上文生成新 SQL
2. **Given** 用户切换数据源, **When** 切换完成, **Then** 自动创建新会话，清空上下文

---

### User Story 4 - 用户反馈 (Priority: P2)

用户对查询结果点赞或点踩，点踩时选择原因。

**Why this priority**: 反馈驱动可信度调整和系统优化。

**Independent Test**: 点赞后审计日志中 feedback 字段更新。

**Acceptance Scenarios**:

1. **Given** 查询结果展示完成, **When** 用户点击赞, **Then** 记录正向反馈
2. **Given** 用户点击踩, **When** 弹出原因选择, **Then** 用户选择原因后提交，进入审核队列

---

### Edge Cases

- 网络断开时 SSE 中断？（前端自动重连或降级为轮询）
- 查询结果为空？（展示"未查询到数据"并建议换个问法）
- 用户快速连续发送多个问题？（排队处理，前一个未完成时禁用发送按钮）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 前端 MUST 提供数据源选择器（仅展示有权限的已启用数据源）
- **FR-002**: 前端 MUST 提供自然语言输入框
- **FR-003**: 前端 MUST 通过 SSE 实时展示查询进度
- **FR-004**: 前端 MUST 展示数据表格和 ECharts 图表
- **FR-005**: 前端 MUST 支持图表类型切换（柱状图、折线图、饼图）
- **FR-006**: 前端 MUST 支持结果导出（表格 CSV、图表 PNG）
- **FR-007**: 前端 MUST 展示口径说明和溯源面板（使用了哪些表、字段、可信度）
- **FR-008**: 前端 MUST 支持用户反馈（赞/踩 + 原因选择）
- **FR-009**: 前端 MUST 支持多轮对话展示和历史会话查看
- **FR-010**: 前端 MUST 在 ECharts 配置无效时降级为纯表格
- **FR-011**: 系统 MUST 自动为新会话生成标题（基于第一个问题的前 20 字）
- **FR-012**: 系统 MUST 支持会话搜索（按标题/问题内容模糊匹配）
- **FR-013**: 前端问答端和后台治理端为同一 Vue 项目，通过路由区分（/query/* 和 /admin/*）

### Key Entities

- **Conversation**: 会话，包含会话ID、用户ID、数据源ID、创建时间、过期时间
- **Message**: 消息，包含消息ID、会话ID、角色（user/assistant）、内容、时间

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户从输入问题到看到结果的等待时间 P95 不超过 35 秒
- **SC-002**: 图表渲染成功率达到 95%（含降级为表格的情况）
- **SC-003**: 用户能在 3 次点击内完成一次完整查询
- **SC-004**: SSE 断线后 5 秒内自动重连

## Assumptions

- 前端使用 Vue 3 + Vite + TypeScript + Element Plus + ECharts
- 状态管理使用 Pinia（保存登录态、当前数据源、会话状态、SSE 任务状态）
- HTTP 请求使用 Axios，SSE 使用原生 EventSource 或封装库
- SSE 用于实时进度推送，查询结果通过 HTTP 返回
- 会话超过 24 小时无交互自动过期
- 前端维护最近 5 轮对话上下文，每次请求透传
- 查询提交为异步模式：POST /api/query/ask 返回 taskId（HTTP 202），前端通过 SSE 获取进度
- SSE 降级方案：断线后可改用 GET /api/query/tasks/{id} 轮询
- 查询结果不缓存（ADR 决策：避免相似问题、相对时间和权限差异导致错误复用）

## Clarifications

### Session 2026-05-16

- Q: 前端技术栈细节？ → A: Vue 3 + Vite + TypeScript + Element Plus + Pinia + Axios + ECharts（文档第 25 节）
- Q: 查询提交模式？ → A: 异步提交，POST 返回 taskId（HTTP 202），前端通过 SSE 获取进度（文档第 26.3 节）
- Q: SSE 断线降级方案？ → A: 改用 GET /api/query/tasks/{id} 轮询，基于 query_task_event 表恢复进度（文档第 23.6 节）
