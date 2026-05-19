# API Contracts: 前端问答端模块

前端问答端消费的后端 API 契约。这些 API 由 Java 网关层提供。

## Base URL

- 查询: `/api/query/*`
- 会话: `/api/conversations/*`
- 数据源: `/api/datasources/*`
- 反馈: `/api/feedback/*`
- 认证: `/api/auth/*`

## Authentication

所有接口需要 JWT Token（Header: `Authorization: Bearer {token}`）。
SSE 接口通过 URL query param 传递 token。

---

## 查询 API

### POST /api/query/ask

提交自然语言查询（异步）。

**Request**:
```json
{
  "datasourceId": 1,
  "question": "上月订单总额是多少",
  "sessionId": "sess_abc123",
  "context": [
    { "role": "user", "content": "上月订单总额是多少" },
    { "role": "assistant", "content": "SELECT SUM(amount) FROM order_info WHERE ..." }
  ]
}
```

**Response 202**:
```json
{
  "code": 202,
  "data": {
    "taskId": 1001,
    "sessionId": "sess_abc123"
  }
}
```

**Response 429** (超出配额):
```json
{
  "code": 429,
  "message": "今日查询次数已达上限"
}
```

---

### GET /api/query/stream/{taskId}?token={jwt}

SSE 实时进度推送。

**Event Types**:
- `progress`: 步骤进度更新
- `result`: 查询结果
- `error`: 错误信息
- `done`: 流结束

**Event: progress**:
```
event: progress
data: {"step":"schema_retrieving","status":"running","message":"正在检索相关表结构..."}
```

**Event: result**:
```
event: result
data: {"sql":"SELECT SUM(amount) ...","columns":[{"name":"total","type":"DECIMAL","label":"总额"}],"rows":[{"total":1250000}],"rowCount":1,"executionTimeMs":1200,"chartOption":{"xAxis":{},"yAxis":{},"series":[]},"explanation":{"usedTables":[...],"usedColumns":[...]}}
```

**Event: error**:
```
event: error
data: {"code":"SQL_VALIDATION_FAILED","message":"SQL 包含不允许的操作","retryable":false}
```

---

### GET /api/query/tasks/{taskId}

轮询查询任务状态（SSE 降级方案）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "taskId": 1001,
    "status": "SUCCESS",
    "progress": [
      { "name": "schema_retrieving", "status": "done" },
      { "name": "sql_generating", "status": "done" },
      { "name": "sql_validating", "status": "done" },
      { "name": "executing", "status": "done" },
      { "name": "visualizing", "status": "done" }
    ],
    "result": {
      "sql": "SELECT SUM(amount) FROM order_info WHERE ...",
      "columns": [{ "name": "total", "type": "DECIMAL", "label": "总额" }],
      "rows": [{ "total": 1250000 }],
      "rowCount": 1,
      "executionTimeMs": 1200,
      "chartOption": { "...": "..." },
      "explanation": { "...": "..." }
    }
  }
}
```

---

## 数据源 API

### GET /api/datasources

获取当前用户有权限的数据源列表。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "订单库",
      "dbType": "MYSQL",
      "host": "192.168.1.100",
      "dbName": "order_db",
      "status": "ENABLED",
      "tableCount": 25
    }
  ]
}
```

---

## 会话 API

### POST /api/conversations

创建新会话。

**Request**:
```json
{
  "datasourceId": 1
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "sessionId": "sess_abc123",
    "expiresAt": "2026-05-17T14:30:00"
  }
}
```

---

### GET /api/conversations

获取用户在指定数据源下的历史会话列表。

**Query Parameters**:
- `datasourceId` (long, required) — 当前选中的数据源 ID
- `keyword` (string, optional) — 按标题或问题内容搜索
- `page` (int, default 1)
- `pageSize` (int, default 20)

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "sessionId": "sess_abc123",
        "datasourceId": 1,
        "datasourceName": "订单库",
        "title": "上月订单总额",
        "messageCount": 4,
        "lastQuestion": "按部门拆分看看",
        "lastMessageAt": "2026-05-15T14:35:00",
        "createdAt": "2026-05-15T14:30:00"
      }
    ],
    "total": 10,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 反馈 API

### POST /api/feedback

提交查询反馈（赞/踩）。

**Request**:
```json
{
  "queryTaskId": 1001,
  "feedbackType": "THUMBS_DOWN",
  "reasonCode": "DATA_INACCURATE",
  "comment": "金额数据不对"
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "反馈提交成功"
}
```

**Response 429**:
```json
{
  "code": 429,
  "message": "今日已对该字段提交过负向反馈"
}
```
