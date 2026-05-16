# API Contracts: 血缘与审计模块

## Base URL

- 管理端: `/api/admin/audit-logs/*`, `/api/admin/lineage/*`, `/api/admin/quotas/*`

## Authentication

所有接口需要 JWT Token。需要 `audit:view` 或 `audit:manage` 权限。

---

## 审计日志 API

### GET /api/admin/audit-logs

分页查询审计日志。

**Query Parameters**:
- `page` (int, default 1)
- `pageSize` (int, default 20, max 100)
- `userId` (long, optional) — 按用户筛选
- `datasourceId` (long, optional) — 按数据源筛选
- `isSuccess` (boolean, optional) — 按成功/失败筛选
- `isSlow` (boolean, optional) — 仅慢查询
- `startDate` (string, optional) — 开始日期 yyyy-MM-dd
- `endDate` (string, optional) — 结束日期 yyyy-MM-dd
- `keyword` (string, optional) — 问题关键词模糊搜索

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "queryTaskId": 1001,
        "userId": 2,
        "username": "zhangsan",
        "datasourceId": 1,
        "datasourceName": "订单库",
        "question": "上月订单总额是多少",
        "generatedSql": "SELECT SUM(amount) FROM order_info WHERE ...",
        "executionTimeMs": 1200,
        "rowCount": 1,
        "isSuccess": true,
        "isSlow": false,
        "feedbackType": "THUMBS_UP",
        "usedTables": ["order_info"],
        "createdAt": "2026-05-15T14:30:00"
      }
    ],
    "total": 1000,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### GET /api/admin/audit-logs/{id}

查询单条审计日志详情（含血缘信息）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "queryTaskId": 1001,
    "userId": 2,
    "username": "zhangsan",
    "question": "上月订单总额是多少",
    "generatedSql": "SELECT SUM(amount) FROM order_info WHERE create_time >= '2026-04-01'",
    "executionTimeMs": 1200,
    "rowCount": 1,
    "isSuccess": true,
    "lineageTables": [
      { "tableName": "order_info", "relationType": "FROM", "alias": null }
    ],
    "lineageColumns": [
      { "sourceTable": "order_info", "sourceColumn": "amount", "expression": "SUM(amount)", "alias": "total", "isAggregated": true },
      { "sourceTable": "order_info", "sourceColumn": "create_time", "expression": null, "alias": null, "isAggregated": false }
    ],
    "createdAt": "2026-05-15T14:30:00"
  }
}
```

---

### GET /api/admin/audit-logs/slow-queries

查询慢查询列表。

**Query Parameters**:
- `page` (int, default 1)
- `pageSize` (int, default 20)
- `datasourceId` (long, optional)
- `startDate` (string, optional)
- `endDate` (string, optional)

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 5,
        "question": "统计所有部门的季度销售额环比",
        "generatedSql": "SELECT ... (complex SQL)",
        "executionTimeMs": 8500,
        "datasourceName": "订单库",
        "username": "lisi",
        "createdAt": "2026-05-14T16:00:00"
      }
    ],
    "total": 12,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### POST /api/admin/audit-logs/export

导出审计日志为 CSV。

**Request**:
```json
{
  "datasourceId": 1,
  "startDate": "2026-05-01",
  "endDate": "2026-05-15"
}
```

**Response 200**: 返回 CSV 文件流 (Content-Type: text/csv)

---

## 血缘 API

### GET /api/admin/lineage/table/{tableMetaId}

查询某张表的血缘关系（哪些查询用到了这张表）。

**Query Parameters**:
- `page` (int, default 1)
- `pageSize` (int, default 20)

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "tableName": "order_info",
    "totalQueryCount": 150,
    "recentQueries": [
      {
        "auditLogId": 1,
        "question": "上月订单总额",
        "relationType": "FROM",
        "createdAt": "2026-05-15T14:30:00"
      }
    ],
    "total": 150,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### GET /api/admin/lineage/column/{columnMetaId}

查询某字段的血缘关系（哪些查询用到了这个字段）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "columnName": "amount",
    "tableName": "order_info",
    "totalQueryCount": 80,
    "aggregationCount": 60,
    "recentQueries": [
      {
        "auditLogId": 1,
        "question": "上月订单总额",
        "expression": "SUM(amount)",
        "isAggregated": true,
        "createdAt": "2026-05-15T14:30:00"
      }
    ]
  }
}
```

---

## 模板提升 API

### POST /api/admin/audit-logs/{id}/promote-template

将审计日志中的查询提升为查询模板。

**Request**:
```json
{
  "templateName": "上月订单总额查询",
  "description": "查询上个月的订单总金额"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "templateId": 10 },
  "message": "模板创建成功"
}
```

---

## 配额 API

### GET /api/admin/quotas

查询配额策略列表。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "subjectType": "GLOBAL",
      "subjectId": null,
      "subjectName": "全局默认",
      "dailyQueryLimit": 100,
      "monthlyCostLimit": 100.00,
      "enabled": true
    }
  ]
}
```

---

### PUT /api/admin/quotas/{id}

更新配额策略。

**Request**:
```json
{
  "dailyQueryLimit": 200,
  "monthlyCostLimit": 500.00,
  "enabled": true
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "配额策略更新成功"
}
```
