# Implementation Plan: 错误处理与降级模块

**Branch**: `017-error-degradation` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

错误处理与降级是跨切面模块，不引入新表，而是在 Java 网关和 Python AI 服务中建立统一的错误处理模式、健康检查机制、降级策略和取消能力。核心目标：单组件故障不导致系统不可用，所有错误返回用户友好的中文提示。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x) + Python 3.13 (FastAPI + LangGraph)

**Primary Dependencies**:
- Java: Spring Boot Actuator, OpenFeign (CircuitBreaker), ScheduledExecutorService
- Python: FastAPI, asyncio (CancellationToken), httpx (health check client)

**Testing**: JUnit 5 (Java), pytest + asyncio (Python)

**Constraints**: Java 120s timeout > Python 100s budget; MVP 单实例部署; 无消息队列

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | N/A | 错误处理不涉及元数据 |
| II. SQL 安全与只读执行 | PASS | 超时取消使用 connection.cancel() |
| III. 三层分离架构 | PASS | Java 和 Python 各自处理各自层的错误 |
| IV. RAG 准入控制 | PASS | Milvus 降级时使用 skills.md 核心表 |
| V. 可信度驱动生成 | N/A | 不涉及可信度 |
| VI. 渐进式 MVP | PASS | 不引入 Sentinel/Hystrix，用简单实现 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/common/
├── health/
│   ├── PythonHealthChecker.java       # 定时健康检查
│   └── ServiceHealthStatus.java       # 服务状态枚举
├── resilience/
│   ├── RetryConfig.java               # 重试配置
│   └── TimeoutConfig.java             # 超时配置
├── exception/
│   ├── GlobalExceptionHandler.java    # (已有) 增加降级错误处理
│   ├── ServiceUnavailableException.java
│   └── QueryCancelledException.java
└── cancel/
    ├── QueryCancelController.java     # POST /api/query/tasks/{id}/cancel
    └── QueryCancelService.java        # 通知 Python 取消

python-service/dataocean/resilience/
├── health.py                          # GET /health, GET /internal/health
├── cancellation.py                    # CancellationToken 管理
├── timeout_budget.py                  # 100s 总时间预算分配
├── milvus_fallback.py                 # Milvus 降级逻辑
└── error_messages.py                  # 用户友好错误消息映射
```

## Implementation Phases

### Phase 1: Java 健康检查 + 服务状态管理

1. PythonHealthChecker: 每 30 秒调用 Python /health
2. 连续 3 次失败 → 标记 UNAVAILABLE，记录日志告警
3. 恢复后自动标记 AVAILABLE
4. 查询请求前检查服务状态，不可用时直接返回友好提示

### Phase 2: Python 时间预算 + LLM 重试

1. timeout_budget.py: 100s 总预算，各节点分配：
   - Schema RAG: 10s
   - SQL Generation (含 1 次重试): 40s
   - SQL Validation: 5s
   - SQL Execution: 30s
   - Chart Generation: 15s
2. LLM 重试: timeout/rate-limit 时重试 1 次，其他错误不重试
3. 各节点执行前检查剩余预算，不足则提前终止

### Phase 3: Milvus 降级

1. milvus_fallback.py: Milvus 连接失败时的降级逻辑
2. 降级方案: 从 skills.md 中提取前 5 张核心表的 DDL 作为上下文
3. 响应中标注"召回精度可能降低"
4. Milvus 恢复后自动切回正常 RAG

### Phase 4: 用户取消

1. Java: POST /api/query/tasks/{id}/cancel → 调用 Python 内部取消 API
2. Python: CancellationToken（线程安全 flag），LangGraph 各节点执行前检查
3. SQL 执行中取消: connection.cancel() 终止正在执行的查询
4. SSE 断开检测: Java 检测 SSE 连接关闭 → 通知 Python 清理

### Phase 5: 统一错误消息

1. error_messages.py: 所有技术错误映射为用户友好中文
2. Java GlobalExceptionHandler 增加降级场景处理
3. 前端展示错误消息，不暴露技术细节

## Key Design Decisions

- **无新表**: 服务状态用内存 AtomicReference 管理，MVP 单实例无需持久化
- **超时层级**: Python 100s < Java 120s，保证 Python 先超时返回，Java 不会 hang
- **CancellationToken**: 简单的 AtomicBoolean flag，各 LangGraph 节点执行前检查
- **Milvus 降级**: 不需要配置，自动检测连接失败并切换到 skills.md 核心表
- **不用 Sentinel/Hystrix**: MVP 阶段用简单的定时健康检查 + 状态标记，避免引入重框架
- **错误消息**: 所有面向用户的错误使用中文，技术细节只记录在日志中
