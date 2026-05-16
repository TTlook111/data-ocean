# Internal API Contracts: SQL 安全与执行沙箱模块

## Base URL

Python 内部服务: `/internal/sql/*`

仅 Agent 模块 (008) 内部调用，不对外暴露。

---

## POST /internal/sql/validate

校验 SQL 安全性并改写（注入行过滤、LIMIT）。

**Request**:
```json
{
  "sql": "SELECT user_id, pay_amount FROM orders WHERE status = 2",
  "datasourceId": 10,
  "allowedTables": ["orders", "refund_record", "users", "products"],
  "userPermissions": {
    "rowFilters": [
      { "tableName": "orders", "condition": "region = '华东'" }
    ],
    "deniedColumns": ["users.phone", "users.id_card"],
    "maskColumns": [
      { "tableName": "users", "columnName": "email", "maskType": "PARTIAL" }
    ]
  }
}
```

**Response 200 (通过)**:
```json
{
  "passed": true,
  "rewrittenSql": "SELECT user_id, pay_amount FROM orders WHERE status = 2 AND region = '华东' LIMIT 10000",
  "violations": [],
  "maskedColumns": []
}
```

**Response 200 (拒绝)**:
```json
{
  "passed": false,
  "rewrittenSql": null,
  "violations": [
    {
      "rule": "FUNCTION",
      "severity": "BLOCK",
      "message": "检测到危险函数: SLEEP",
      "detail": "SLEEP(5)"
    }
  ],
  "maskedColumns": []
}
```

**Response 200 (通过但有脱敏)**:
```json
{
  "passed": true,
  "rewrittenSql": "SELECT u.user_id, u.email FROM users u WHERE u.region = '华东' LIMIT 10000",
  "violations": [],
  "maskedColumns": ["users.email"]
}
```

**Response 200 (列权限拒绝)**:
```json
{
  "passed": false,
  "rewrittenSql": null,
  "violations": [
    {
      "rule": "COLUMN",
      "severity": "BLOCK",
      "message": "无权访问字段 users.phone",
      "detail": "users.phone in deniedColumns"
    }
  ],
  "maskedColumns": []
}
```

---

## POST /internal/sql/execute

在沙箱环境中执行已校验的 SQL。

**Request**:
```json
{
  "sql": "SELECT user_id, pay_amount FROM orders WHERE status = 2 AND region = '华东' LIMIT 10000",
  "datasourceId": 10,
  "connectionConfig": {
    "host": "10.0.1.100",
    "port": 3306,
    "database": "order_db",
    "username": "readonly_user",
    "encryptedPassword": "AES256_ENCRYPTED_STRING"
  },
  "timeoutSeconds": 30,
  "maxRows": 10000,
  "maskedColumns": ["users.email"]
}
```

**Response 200 (成功)**:
```json
{
  "success": true,
  "data": [
    { "user_id": 1001, "pay_amount": 299.00 },
    { "user_id": 1002, "pay_amount": 150.50 }
  ],
  "columns": [
    { "name": "user_id", "type": "BIGINT", "comment": null, "masked": false },
    { "name": "pay_amount", "type": "DECIMAL(10,2)", "comment": null, "masked": false }
  ],
  "rowCount": 2,
  "executionTimeMs": 120,
  "error": null,
  "errorType": null
}
```

**Response 200 (超时)**:
```json
{
  "success": false,
  "data": null,
  "columns": null,
  "rowCount": 0,
  "executionTimeMs": 30000,
  "error": "查询执行超时（30s），请简化查询条件",
  "errorType": "TIMEOUT"
}
```

**Response 200 (SQL 语法错误)**:
```json
{
  "success": false,
  "data": null,
  "columns": null,
  "rowCount": 0,
  "executionTimeMs": 5,
  "error": "Unknown column 'refund_amt' in 'field list'",
  "errorType": "SYNTAX"
}
```

**Response 200 (连接失败)**:
```json
{
  "success": false,
  "data": null,
  "columns": null,
  "rowCount": 0,
  "executionTimeMs": 5000,
  "error": "无法连接到数据源，请检查网络配置",
  "errorType": "CONNECTION"
}
```

**Response 503 (连接池满)**:
```json
{
  "success": false,
  "error": "系统繁忙，请稍后重试",
  "errorType": "POOL_EXHAUSTED"
}
```

---

## DELETE /internal/sql/pools/{datasourceId}

销毁指定数据源的连接池。数据源禁用/删除时由 Java 调用。

**Response 200**:
```json
{
  "datasourceId": 10,
  "destroyed": true,
  "message": "连接池已销毁"
}
```

**Response 404**:
```json
{
  "datasourceId": 10,
  "destroyed": false,
  "message": "该数据源无活跃连接池"
}
```

---

## GET /internal/sql/pools/status

获取连接池状态（运维用）。

**Response 200**:
```json
{
  "globalConnectionCount": 35,
  "maxGlobal": 50,
  "pools": [
    {
      "datasourceId": 10,
      "activeConnections": 3,
      "idleConnections": 7,
      "maxConnections": 10,
      "createdAt": "2026-05-16T10:00:00",
      "lastUsedAt": "2026-05-16T14:30:00"
    }
  ]
}
```
