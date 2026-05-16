# Feature Specification: 错误处理与降级模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: NL2SQL 链路涉及数据库、向量库、LLM、Python 服务和 Java 网关，必须有完善的降级策略。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - LLM 调用失败时自动重试和降级 (Priority: P1)

LLM API 超时或限流时，系统自动重试 1 次，仍失败则返回友好提示。

**Why this priority**: LLM 是最不稳定的外部依赖，必须有容错机制。

**Independent Test**: 模拟 LLM API 超时，系统重试后返回友好错误提示而非技术错误。

**Acceptance Scenarios**:

1. **Given** LLM API 首次调用超时, **When** 系统重试, **Then** 第二次成功则正常返回结果
2. **Given** LLM API 两次都超时, **When** 重试耗尽, **Then** 返回"AI 服务繁忙，请稍后再试"
3. **Given** LLM 返回非法 JSON, **When** 解析失败, **Then** 走 Agent 重试流程（最多 3 次）

---

### User Story 2 - 向量库不可用时降级 (Priority: P1)

Milvus 不可用时，系统降级为使用 skills.md 中的核心表作为上下文。

**Why this priority**: 向量库故障不应导致整个系统不可用。

**Independent Test**: 关闭 Milvus 后，用户仍能查询（使用降级方案）。

**Acceptance Scenarios**:

1. **Given** Milvus 连接失败, **When** 用户发起查询, **Then** 系统使用 skills.md 中前 5 张核心表作为上下文，并提示"召回精度可能降低"
2. **Given** Milvus 恢复, **When** 下次查询, **Then** 自动恢复正常 RAG 召回

---

### User Story 3 - Python 服务不可用时 Java 网关告警 (Priority: P1)

Java 网关检测到 Python 服务不可用时，返回友好提示并告警运维。

**Why this priority**: 服务间通信故障是分布式系统的常见问题，必须有健康检查和告警。

**Independent Test**: Python 服务停止后，Java 健康检查失败，用户收到友好提示。

**Acceptance Scenarios**:

1. **Given** Java 每 30 秒调用 Python /health, **When** 连续 3 次失败, **Then** 标记 Python 为不可用，告警运维
2. **Given** Python 被标记为不可用, **When** 用户发起查询, **Then** 返回"AI 服务暂时不可用，请稍后再试"
3. **Given** Python 恢复, **When** 健康检查成功, **Then** 自动恢复服务标记

---

### User Story 4 - 用户取消查询 (Priority: P2)

用户在等待过程中取消查询，系统终止正在执行的操作并释放资源。

**Why this priority**: 取消能力提升用户体验，避免用户被长时间阻塞。

**Independent Test**: 用户取消后，正在执行的 SQL 被终止，资源被释放。

**Acceptance Scenarios**:

1. **Given** 查询正在执行中, **When** 用户点击取消, **Then** Java 通知 Python 取消，Python 终止当前操作
2. **Given** SQL 正在数据库执行中, **When** 收到取消信号, **Then** 调用 connection.cancel() 终止查询

---

### Edge Cases

- 数据库连接池耗尽？（返回"系统繁忙，请稍后再试"，不排队等待）
- 多个组件同时故障？（按优先级返回最关键的错误信息）
- 超时设置：Java 120 秒、Python 100 秒，如何避免级联超时？（Python 先超时返回，Java 不会 hang）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在 LLM 调用失败时自动重试 1 次
- **FR-002**: 系统 MUST 在向量库不可用时降级为核心表上下文
- **FR-003**: 系统 MUST 实现 Python 服务健康检查（每 30 秒，连续 3 次失败标记不可用）
- **FR-004**: 系统 MUST 在 Python 不可用时返回友好提示并告警
- **FR-005**: 系统 MUST 支持用户取消正在执行的查询
- **FR-006**: 系统 MUST 设置 Java 端 120 秒超时、Python 端 100 秒总时间预算
- **FR-007**: 系统 MUST 在 SQL 执行超时时返回"查询超时，请缩小查询范围"
- **FR-008**: 系统 MUST 在 SSE 连接断开时检测并清理后端资源
- **FR-009**: 所有错误提示 MUST 使用用户友好的自然语言，不暴露技术细节

### Key Entities

- **ServiceHealth**: 服务健康状态，包含服务名、状态、最后检查时间、连续失败次数
- **CancellationToken**: 取消令牌，包含 taskId、是否已取消、取消时间

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 单组件故障时系统可用性达到 99%（通过降级保证）
- **SC-002**: 所有错误场景都返回用户友好提示，不暴露堆栈或技术错误
- **SC-003**: 用户取消后 5 秒内资源完全释放
- **SC-004**: Python 服务恢复后 30 秒内自动恢复正常服务

## Assumptions

- 健康检查使用 Python FastAPI 的 /health 端点
- 取消机制使用 CancellationToken（线程安全 flag），LangGraph 各节点执行前检查
- 降级方案不需要额外配置，系统自动判断并执行
- Python AI 服务 MVP 阶段单实例部署，CancellationToken 无需跨进程协调

## Clarifications

### Session 2026-05-16

- Q: Python 服务部署模式？ → A: MVP 单实例部署，不考虑水平扩展
