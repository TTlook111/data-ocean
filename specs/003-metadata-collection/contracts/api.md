# API Contracts: 元数据采集模块

**Date**: 2026-05-16 | **Base Path**: `/api/admin`

## Admin APIs (需要 ADMIN 角色)

### POST /api/admin/datasources/{datasourceId}/sync-schema — 触发全量同步

**Description**: 手动触发指定数据源的全量元数据同步

**Request**:
```json
{
  "includeStatistics": true,
  "excludeTablePrefixes": ["tmp_", "bak_"]
}
```

**Response** (202 Accepted):
```json
{
  "code": 200,
  "data": {
    "taskId": 42,
    "datasourceId": 1,
    "status": "PENDING",
    "message": "同步任务已创建"
  }
}
```

**Errors**:
- 404: 数据源不存在
- 409: 该数据源已有正在执行的同步任务
- 503: 数据源连接不可用

---

### GET /api/admin/datasources/{datasourceId}/sync-tasks — 同步任务列表

**Query Params**: `page=1&size=20&status=SUCCESS`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 42,
        "datasourceId": 1,
        "triggerType": "MANUAL",
        "triggeredBy": "admin",
        "status": "SUCCESS",
        "progressTotal": 150,
        "progressCurrent": 150,
        "snapshotId": 10,
        "startedAt": "2026-05-16T10:00:00",
        "finishedAt": "2026-05-16T10:00:45",
        "durationSeconds": 45
      }
    ],
    "total": 5,
    "page": 1,
    "size": 20
  }
}
```

---

### GET /api/admin/datasources/{datasourceId}/sync-tasks/{taskId} — 同步任务详情（含进度）

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "id": 42,
    "datasourceId": 1,
    "triggerType": "MANUAL",
    "status": "RUNNING",
    "progressTotal": 150,
    "progressCurrent": 87,
    "progressPercent": 58,
    "startedAt": "2026-05-16T10:00:00",
    "finishedAt": null,
    "errorMessage": null
  }
}
```

---

### GET /api/admin/datasources/{datasourceId}/snapshots — 快照列表

**Query Params**: `page=1&size=10`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 10,
        "datasourceId": 1,
        "snapshotVersion": 3,
        "status": "DRAFT",
        "tableCount": 150,
        "columnCount": 2340,
        "totalRowsEstimate": 5000000,
        "qualityScore": null,
        "schemaHash": "a1b2c3d4e5f6...",
        "createdAt": "2026-05-16T10:00:45"
      }
    ],
    "total": 3,
    "page": 1,
    "size": 10
  }
}
```

---

### GET /api/admin/snapshots/{snapshotId} — 快照详情（含表列表）

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "id": 10,
    "datasourceId": 1,
    "snapshotVersion": 3,
    "status": "DRAFT",
    "tableCount": 150,
    "columnCount": 2340,
    "tables": [
      {
        "id": 100,
        "tableName": "orders",
        "tableComment": "订单主表",
        "tableType": "TABLE",
        "rowCountEstimate": 500000,
        "columnCount": 18,
        "governanceStatus": "DISCOVERED"
      }
    ],
    "createdAt": "2026-05-16T10:00:45"
  }
}
```

---

### GET /api/admin/snapshots/{snapshotId}/tables/{tableName} — 表详情（含字段列表）

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "id": 100,
    "tableName": "orders",
    "tableComment": "订单主表",
    "engine": "InnoDB",
    "charset": "utf8mb4",
    "rowCountEstimate": 500000,
    "governanceStatus": "DISCOVERED",
    "columns": [
      {
        "id": 1001,
        "columnName": "id",
        "columnComment": "订单ID",
        "dataType": "BIGINT",
        "isNullable": false,
        "isPrimaryKey": true,
        "ordinalPosition": 1,
        "nullRate": 0.0,
        "governanceStatus": "DISCOVERED",
        "confidenceScore": null
      },
      {
        "id": 1002,
        "columnName": "user_id",
        "columnComment": "用户ID",
        "dataType": "BIGINT",
        "isNullable": false,
        "isPrimaryKey": false,
        "ordinalPosition": 2,
        "nullRate": 0.0,
        "governanceStatus": "DISCOVERED",
        "confidenceScore": null
      }
    ],
    "indexes": [
      { "indexName": "PRIMARY", "columns": ["id"], "unique": true },
      { "indexName": "idx_user_id", "columns": ["user_id"], "unique": false }
    ],
    "relations": [
      {
        "sourceColumn": "user_id",
        "targetTable": "users",
        "targetColumn": "id",
        "relationType": "FK"
      }
    ]
  }
}
```

---

### GET /api/admin/snapshots/{snapshotId}/diff/{compareSnapshotId} — 快照差异对比

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "baseSnapshotId": 9,
    "compareSnapshotId": 10,
    "summary": {
      "tablesAdded": 2,
      "tablesRemoved": 0,
      "columnsAdded": 15,
      "columnsRemoved": 3,
      "columnsModified": 5,
      "commentsChanged": 8
    },
    "changes": [
      {
        "changeType": "TABLE_ADDED",
        "tableName": "order_refunds",
        "columnName": null,
        "oldValue": null,
        "newValue": null,
        "riskLevel": "LOW"
      },
      {
        "changeType": "COLUMN_REMOVED",
        "tableName": "orders",
        "columnName": "legacy_status",
        "oldValue": "VARCHAR(20)",
        "newValue": null,
        "riskLevel": "HIGH"
      }
    ]
  }
}
```

---

## Authentication

所有接口需要 JWT Token + ADMIN 角色。

## Common Error Response

```json
{
  "code": 404,
  "message": "数据源不存在"
}
```
