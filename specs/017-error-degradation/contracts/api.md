# API Contracts: 错误处理与降级模块

## Overview

本模块不引入独立的业务 API，而是定义跨模块的错误处理契约、健康检查端点和取消机制。

---

## Health Check Endpoints

### GET /health (Python, public)

Python 服务健康检查（Java 定时调用）。

**Response 200** (健康):
```json
{
  "status": "healthy",
  "timestamp": "2026-05-16T10:30:00Z",
  "components": {
    "milvus": "up",
    "llm_api": "up"
  }
}
```

**Response 503** (不健康):
```json
{
  "status": "unhealthy",
  "timestamp": "2026-05-16T10:30:00Z",
  "components": {
    "milvus": "down",
    "llm_api": "up"
  }
}
```

### ~~GET /internal/health~~ (Python, internal) — 已于 2026-09-25 移除

**该端点已删除，不再存在（请求返回 404）。** 原因：它没有任何调用方（Java 的 `PythonHealthChecker` 调用的是公开的 `/health`），却在注册时遗漏了 `X-Internal-Token` 校验，是唯一未受保护的 `/internal/*` 路径，并且会把底层异常原文通过 `{"error": str(e)}` 返回。无调用方的未认证接口属纯攻击面，故直接删除而非加固。

公开的 `GET /health`（返回 `{"status": "ok"}`）不受影响，Java 侧健康检查继续使用它。若将来需要内部健康详情，请新建独立 router 并挂上 `verify_internal_token`。

以下为该端点被删除前的契约记录，仅作历史参考。

**Response 200**:
```json
{
  "status": "healthy",
  "uptime_seconds": 86400,
  "components": {
    "milvus": { "status": "up", "latency_ms": 12 },
    "llm_api": { "status": "up", "latency_ms": 850 }
  },
  "active_tasks": 3
}
```

---

## Query Cancellation

### POST /api/query/tasks/{taskId}/cancel (Java, user-facing)

用户取消正在执行的查询。

**Path Parameters**:
- `taskId` (string) — 查询任务ID

**Response 200**:
```json
{
  "code": 200,
  "message": "查询已取消"
}
```

**Response 404** (任务不存在或已完成):
```json
{
  "code": 404,
  "message": "任务不存在或已完成"
}
```

### POST /internal/tasks/{taskId}/cancel (Python, internal)

Java 通知 Python 取消任务。

**Path Parameters**:
- `taskId` (string) — 查询任务ID

**Response 200**:
```json
{
  "cancelled": true,
  "task_id": "task-abc123"
}
```

**Response 404** (任务不存在):
```json
{
  "cancelled": false,
  "error": "task_not_found"
}
```

---

## Error Response Format (统一)

所有 API 的错误响应遵循统一格式：

```json
{
  "code": 500,
  "message": "用户友好的中文错误提示",
  "traceId": "trace-xyz789"
}
```

- `code`: HTTP 状态码
- `message`: 面向用户的中文提示，不包含技术细节
- `traceId`: 用于日志追踪的唯一标识（方便运维排查）

---

## Degradation Indicators in Query Response

查询响应中包含降级标识：

```json
{
  "code": 200,
  "data": {
    "taskId": "task-abc123",
    "sql": "SELECT ...",
    "rows": [...],
    "degraded": true,
    "degradationNotes": [
      "向量检索不可用，已使用核心表上下文（召回精度可能降低）"
    ]
  }
}
```

---

## Timeout Configuration

| Layer | Timeout | Description |
|-------|---------|-------------|
| Frontend SSE | 130s | 前端 EventSource 超时 |
| Java → Python (RestClient/httpx) | 120s | Java 调用 Python 的总超时 |
| Python total budget | 100s | Python 内部总时间预算 |
| Python → LLM (single call) | 30s | 单次 LLM 调用超时 |
| Python → Milvus | 10s | 向量检索超时 |
| Python → MySQL (execution) | 30s | SQL 执行超时 |

---

## Java Health Check Behavior

```
Every 30s: GET Python /health
  ├── Success → reset failure counter, mark AVAILABLE
  └── Failure → increment counter
       ├── counter < 3 → keep current status
       └── counter >= 3 → mark UNAVAILABLE, log WARN

When UNAVAILABLE:
  └── User query → return 503 "AI 服务暂时不可用，请稍后再试"

When Python recovers:
  └── Next health check success → mark AVAILABLE, reset counter
```

---

## LLM Retry Behavior

```
Call LLM:
  ├── Success → return result
  └── Failure
       ├── timeout/429/5xx → wait 2s → retry once
       │    ├── Success → return result
       │    └── Failure → return user-friendly error
       └── 4xx (other) → return user-friendly error (no retry)
```

---

## SSE Disconnect Handling

```
SSE connection established:
  ├── onCompletion() → normal end, no action
  ├── onTimeout() → call POST /internal/tasks/{taskId}/cancel
  └── onError() → call POST /internal/tasks/{taskId}/cancel
```
