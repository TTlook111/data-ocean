# Tasks: 错误处理与降级模块

**Input**: Design documents from `specs/017-error-degradation/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Java 健康检查包结构 `backend/src/main/java/com/dataocean/common/health/`
- [ ] T002 创建 Java 容错配置包结构 `backend/src/main/java/com/dataocean/common/resilience/`
- [ ] T003 创建 Java 取消功能包结构 `backend/src/main/java/com/dataocean/common/cancel/`
- [ ] T004 创建 Python 容错模块包结构 `python-service/dataocean/resilience/`，包含 __init__.py, health.py, cancellation.py, timeout_budget.py, milvus_fallback.py, error_messages.py

## Phase 2: User Story 3 (P1) — Java 健康检查 + 服务状态管理

**Goal**: Python 服务停止后，Java 健康检查失败，用户收到友好提示
**Independent Test**: 连续 3 次健康检查失败后标记不可用，用户查询返回友好提示

- [ ] T005 [US3] 创建服务状态枚举 `backend/src/main/java/com/dataocean/common/health/ServiceHealthStatus.java`，定义 AVAILABLE, UNAVAILABLE, DEGRADED 状态及最后检查时间、连续失败次数字段
- [ ] T006 [US3] 创建健康检查器 `backend/src/main/java/com/dataocean/common/health/PythonHealthChecker.java`，使用 @Scheduled 每 30 秒调用 Python GET /health，连续 3 次失败标记 UNAVAILABLE（AtomicReference 存储状态），恢复后自动标记 AVAILABLE，失败时记录 WARN 日志
- [ ] T007 [US3] 在查询接口入口处增加服务状态检查：Python 不可用时直接返回 HTTP 503 + 友好提示"AI 服务暂时不可用，请稍后再试"，不发起实际调用
- [ ] T008 [US3] 创建 Python 健康检查端点 `python-service/dataocean/resilience/health.py`，实现 GET /health（返回 200 + 基本状态）和 GET /internal/health（返回详细状态：Milvus 连接、LLM 可达性）

## Phase 3: User Story 1 (P1) — Python 时间预算 + LLM 重试

**Goal**: LLM API 超时重试后返回友好错误提示而非技术错误
**Independent Test**: 模拟 LLM 超时，系统重试 1 次后返回友好提示

- [ ] T009 [US1] 创建 Python 时间预算管理器 `python-service/dataocean/resilience/timeout_budget.py`，实现 TimeoutBudget 类：初始化 100s 总预算，分配各节点预算（Schema RAG: 10s, SQL Generation: 40s, SQL Validation: 5s, SQL Execution: 30s, Chart Generation: 15s），提供 remaining() 和 allocate(node_name) 方法，各节点执行前检查剩余预算不足则抛出 BudgetExhaustedException
- [ ] T010 [US1] 在 LangGraph 各 LLM 调用节点中实现重试逻辑：timeout 或 rate-limit 错误时重试 1 次（使用 timeout_budget 分配的时间），其他错误不重试，重试仍失败则抛出带有用户友好消息的异常
- [ ] T011 [US1] 创建 Java 超时配置 `backend/src/main/java/com/dataocean/common/resilience/TimeoutConfig.java`，配置 OpenFeign 调用 Python 的超时为 120s（大于 Python 100s 预算），确保 Python 先超时返回

## Phase 4: User Story 2 (P1) — Milvus 降级

**Goal**: 关闭 Milvus 后，用户仍能查询（使用降级方案）
**Independent Test**: Milvus 不可用时使用 skills.md 核心表作为上下文

- [ ] T012 [US2] 创建 Python Milvus 降级模块 `python-service/dataocean/resilience/milvus_fallback.py`，实现 get_fallback_context(datasource_id) 函数：从 skills.md 中提取前 5 张核心表的 DDL 作为上下文，返回降级 schema 信息
- [ ] T013 [US2] 在 LangGraph Schema_Retriever_Node 中集成降级逻辑：try 正常 Milvus 向量检索，except 连接失败时调用 milvus_fallback，在响应中标注 degraded=true 和提示"召回精度可能降低"
- [ ] T014 [US2] 实现 Milvus 自动恢复检测：每次查询时尝试正常连接，连接成功则自动切回正常 RAG（无需手动干预）

## Phase 5: User Story 4 (P2) — 用户取消

**Goal**: 用户取消后，正在执行的 SQL 被终止，资源被释放
**Independent Test**: 取消后 5 秒内资源完全释放

- [ ] T015 [P] [US4] 创建 Python 取消令牌管理 `python-service/dataocean/resilience/cancellation.py`，实现 CancellationToken 类（AtomicBoolean flag + task_id），提供 cancel()、is_cancelled() 方法，以及 CancellationRegistry（dict 存储 task_id → token 映射）
- [ ] T016 [P] [US4] 创建 Java 取消 Controller `backend/src/main/java/com/dataocean/common/cancel/QueryCancelController.java`，实现 POST /api/query/tasks/{taskId}/cancel 端点，调用 Python 内部取消 API
- [ ] T017 [US4] 创建 Java 取消 Service `backend/src/main/java/com/dataocean/common/cancel/QueryCancelService.java`，通过 OpenFeign 调用 Python POST /internal/tasks/{taskId}/cancel
- [ ] T018 [US4] 在 Python LangGraph 各节点执行前检查 CancellationToken.is_cancelled()，已取消则抛出 QueryCancelledException 终止流程
- [ ] T019 [US4] 在 Python SQL 执行节点中实现数据库查询取消：收到取消信号时调用 connection.cancel() 终止正在执行的 SQL
- [ ] T020 [US4] 在 Java 端实现 SSE 断开检测：SSE 连接关闭时自动调用 QueryCancelService 通知 Python 清理资源

## Phase 6: 统一错误消息

- [ ] T021 [P] 创建 Python 错误消息映射 `python-service/dataocean/resilience/error_messages.py`，定义所有技术错误到用户友好中文消息的映射（LLM_TIMEOUT → "AI 服务繁忙，请稍后再试", MILVUS_UNAVAILABLE → "知识库暂时不可用，已使用降级方案", SQL_TIMEOUT → "查询超时，请缩小查询范围", BUDGET_EXHAUSTED → "处理时间超出限制，请简化问题", CANCELLED → "查询已取消"）
- [ ] T022 [P] 创建 Java 异常类 `backend/src/main/java/com/dataocean/common/exception/ServiceUnavailableException.java` 和 `QueryCancelledException.java`，包含用户友好消息字段
- [ ] T023 扩展 Java GlobalExceptionHandler（已有文件），增加 ServiceUnavailableException（返回 503）、QueryCancelledException（返回 499）、Python 服务超时（返回 504 + 友好提示）的处理逻辑
- [ ] T024 确保所有面向用户的错误响应使用统一 JSON 格式：{code, message, data}，message 为中文友好提示，技术细节只记录在服务端日志

## Phase 7: Polish & Cross-Cutting

- [ ] T025 创建 Java 重试配置 `backend/src/main/java/com/dataocean/common/resilience/RetryConfig.java`，配置 OpenFeign 调用 Python 的重试策略（仅对 5xx 和超时重试 1 次，非幂等请求不重试）
- [ ] T026 实现连接池耗尽处理：数据库连接池满时返回"系统繁忙，请稍后再试"而非技术错误
- [ ] T027 实现多组件同时故障时的优先级错误返回：Python 不可用 > Milvus 降级 > LLM 超时，返回最关键的错误信息

## Phase 8: Service Health Dashboard

- [ ] T028 在 Java 后端添加 GET /api/admin/system/health 接口：返回各服务当前健康状态（Python 服务、Milvus、Redis、MySQL）、最后检查时间、连续失败次数
- [ ] T029 [Frontend] 创建服务健康状态页面 `frontend/src/views/admin/system/ServiceHealth.vue`：展示各服务状态（绿/黄/红）、最后检查时间、历史可用率
- [ ] T030 在 HealthCheckScheduler 中，当服务状态变更时（可用→不可用 或 不可用→恢复），通过 NotificationService 发送通知给超级管理员

## Dependencies

```
T001-T004 → T005-T024
T005 → T006 → T007
T008 → T006 (Java 调用 Python /health)
T009 → T010
T011 → T006 (超时配置影响健康检查)
T012 → T013 → T014
T015 → T018, T019
T016 → T017 → T020
T015 → T017 (Python 取消 API 依赖 CancellationToken)
T021 → T010, T013 (错误消息被各节点使用)
T022 → T023
```

## Implementation Strategy

MVP-first: Phase 2 实现健康检查（最基本的服务可用性保障），Phase 3 实现时间预算和 LLM 重试（核心容错），Phase 4 实现 Milvus 降级（关键依赖容错），Phase 5 实现用户取消（体验优化），Phase 6 统一错误消息。本模块是跨切面模块，各 Phase 完成后需要与对应的业务模块集成验证。Java 和 Python 可并行开发。
