# Internal API Contracts: NL2SQL Agent 模块

## Base URL

Python 内部服务: `/internal/query/*`, `/internal/tasks/*`

仅 Java 网关层通过 OpenFeign 调用，不对外暴露。

---

## POST /internal/query/execute

主入口。提交查询任务，返回 SSE 事件流。

**Request**:
```json
{
  "taskId": "task-uuid-001",
  "datasourceId": 10,
  "userId": 5,
  "question": "华东区上月退款金额",
  "conversationHistory": [
    { "role": "user", "content": "上月订单总额" },
    { "role": "assistant", "content": "上月订单总额为 ¥1,234,567" }
  ],
  "userPermissions": {
    "rowFilters": [
      { "tableName": "orders", "condition": "region = '华东'" }
    ],
    "deniedColumns": ["users.phone", "users.id_card"],
    "maskColumns": [
      { "tableName": "users", "columnName": "email", "maskType": "PARTIAL" }
    ],
    "allowedTables": ["orders", "refund_record", "users", "products"]
  },
  "activeSnapshotId": 5
}
```

**Response**: SSE Stream (Content-Type: text/event-stream)

```
event: progress
data: {"taskId":"task-uuid-001","node":"SCHEMA_RETRIEVER","status":"started","message":"正在召回相关表","retryCount":0,"elapsedMs":100}

event: progress
data: {"taskId":"task-uuid-001","node":"SCHEMA_RETRIEVER","status":"completed","message":"召回 8 张相关表","retryCount":0,"elapsedMs":1200}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_GENERATOR","status":"started","message":"正在生成 SQL","retryCount":0,"elapsedMs":1300}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_GENERATOR","status":"completed","message":"SQL 生成完成","retryCount":0,"elapsedMs":4500}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_VALIDATOR","status":"started","message":"正在校验 SQL 安全性","retryCount":0,"elapsedMs":4600}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_VALIDATOR","status":"completed","message":"校验通过","retryCount":0,"elapsedMs":4800}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_EXECUTOR","status":"started","message":"正在执行查询","retryCount":0,"elapsedMs":4900}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_EXECUTOR","status":"completed","message":"查询完成，返回 15 行数据","retryCount":0,"elapsedMs":6200}

event: progress
data: {"taskId":"task-uuid-001","node":"DATA_VISUALIZER","status":"started","message":"正在生成图表","retryCount":0,"elapsedMs":6300}

event: result
data: {"taskId":"task-uuid-001","status":"COMPLETED","sql":"SELECT region, SUM(actual_refund) AS total_refund FROM refund_record WHERE region = '华东' AND refund_time >= '2026-04-01' AND refund_time < '2026-05-01' GROUP BY region","sqlExplanation":"查询华东区2026年4月的退款总金额","data":[{"region":"华东","total_refund":156789.50}],"columns":[{"name":"region","type":"VARCHAR","comment":"区域"},{"name":"total_refund","type":"DECIMAL","comment":"退款总额"}],"rowCount":1,"chartConfig":{"type":"bar","xAxis":{"data":["华东"]},"series":[{"data":[156789.50]}]},"usedTables":["refund_record"],"usedColumns":["region","actual_refund","refund_time"],"retryCount":0,"totalTimeMs":7500}
```

**SSE Error Event** (重试场景):
```
event: progress
data: {"taskId":"task-uuid-001","node":"SQL_EXECUTOR","status":"failed","message":"SQL 执行失败: Unknown column 'refund_amt'","retryCount":0,"elapsedMs":5500}

event: progress
data: {"taskId":"task-uuid-001","node":"SQL_GENERATOR","status":"started","message":"正在重新生成 SQL（第 1 次重试）","retryCount":1,"elapsedMs":5600}
```

**SSE Error Event** (最终失败):
```
event: error
data: {"taskId":"task-uuid-001","status":"FAILED","error":"MAX_RETRY_EXCEEDED","message":"多次尝试后仍无法生成正确 SQL，请换个问法或联系管理员","retryCount":3,"totalTimeMs":45000}
```

**SSE Error Event** (安全拒绝):
```
event: error
data: {"taskId":"task-uuid-001","status":"FAILED","error":"SECURITY_VIOLATION","message":"生成的 SQL 包含不安全操作，已拦截","retryCount":0,"totalTimeMs":5000}
```

**SSE Error Event** (超时):
```
event: error
data: {"taskId":"task-uuid-001","status":"FAILED","error":"TIMEOUT","message":"查询超时（100s），请简化问题后重试","retryCount":2,"totalTimeMs":100000}
```

---

## POST /internal/tasks/{taskId}/cancel

取消正在执行的查询任务。

**Response 200**:
```json
{
  "taskId": "task-uuid-001",
  "cancelled": true,
  "message": "任务已标记取消"
}
```

**Response 404** (任务不存在或已完成):
```json
{
  "error": "TASK_NOT_FOUND",
  "message": "任务不存在或已完成"
}
```

---

## GET /internal/query/health

Agent 服务健康检查。

**Response 200**:
```json
{
  "status": "healthy",
  "activeTasks": 3,
  "llmAvailable": true,
  "ragAvailable": true
}
```
