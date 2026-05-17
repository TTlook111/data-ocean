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

### GET /internal/health (Python, internal)

详细健康状态（含延迟指标），供 Java 内部调用。

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
| Java → Python (OpenFeign/httpx) | 120s | Java 调用 Python 的总超时 |
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
