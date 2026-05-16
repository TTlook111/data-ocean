# API Contracts: 元数据治理模块

**Date**: 2026-05-16 | **Base Path**: `/api/admin`

## Quality Check APIs

### POST /api/admin/snapshots/{snapshotId}/quality-check — 触发质量校验

**Description**: 对指定快照执行五维质量校验，生成问题清单和质量分

**Request** (可选参数):
```json
{
  "dimensions": ["COMPLETENESS", "ACCURACY", "CONSISTENCY"],
  "tableNames": ["orders", "users"]
}
```

**Notes**: dimensions 为空时执行全部五维校验，tableNames 为空时校验所有表

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "snapshotId": 10,
    "qualityScore": 72.5,
    "dimensionScores": {
      "COMPLETENESS": 65.0,
      "ACCURACY": 80.0,
      "CONSISTENCY": 70.0,
      "TIMELINESS": 90.0,
      "TRACEABILITY": 75.0
    },
    "issueCount": {
      "HIGH": 3,
      "MEDIUM": 12,
      "LOW": 8
    },
    "totalIssues": 23
  }
}
```

**Errors**:
- 404: 快照不存在
- 409: 快照已在校验中

---

### GET /api/admin/snapshots/{snapshotId}/quality-issues — 问题清单

**Query Params**: `page=1&size=20&dimension=COMPLETENESS&severity=HIGH&status=OPEN&tableName=orders`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 101,
        "dimension": "COMPLETENESS",
        "severity": "HIGH",
        "tableName": "orders",
        "columnName": null,
        "issueDescription": "表 orders 缺少表注释",
        "suggestion": "建议补充表注释，描述该表的业务含义",
        "status": "OPEN",
        "assigneeId": null,
        "assigneeName": null,
        "createdAt": "2026-05-16T10:05:00"
      },
      {
        "id": 102,
        "dimension": "COMPLETENESS",
        "severity": "MEDIUM",
        "tableName": "orders",
        "columnName": "status",
        "issueDescription": "字段 orders.status 缺少字段注释",
        "suggestion": "建议补充字段注释，说明该字段的取值含义",
        "status": "OPEN",
        "assigneeId": null,
        "assigneeName": null,
        "createdAt": "2026-05-16T10:05:00"
      }
    ],
    "total": 23,
    "page": 1,
    "size": 20
  }
}
```

---

### PATCH /api/admin/quality-issues/{issueId}/status — 处理问题

**Request**:
```json
{
  "status": "RESOLVED",
  "resolutionNote": "已补充表注释"
}
```

**Valid status transitions**:
- OPEN → CONFIRMED / REJECTED
- CONFIRMED → RESOLVED / REJECTED

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "id": 101,
    "status": "RESOLVED",
    "resolvedBy": "admin",
    "resolvedAt": "2026-05-16T11:00:00"
  }
}
```

---

### PATCH /api/admin/quality-issues/batch-status — 批量处理问题

**Request**:
```json
{
  "issueIds": [101, 102, 103],
  "status": "CONFIRMED"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "updated": 3 }
}
```

---

### POST /api/admin/quality-issues/{issueId}/assign — 分派问题

**Request**:
```json
{
  "assigneeId": 5
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "id": 101, "assigneeId": 5, "assigneeName": "张三" }
}
```

---

## Governance Status APIs

### PATCH /api/admin/snapshots/{snapshotId}/tables/{tableName}/governance-status — 修改表治理状态

**Request**:
```json
{
  "governanceStatus": "NORMAL",
  "remark": "确认该表可用于查询"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "tableName": "orders",
    "oldStatus": "DISCOVERED",
    "newStatus": "NORMAL"
  }
}
```

---

### PATCH /api/admin/snapshots/{snapshotId}/columns/{columnId}/governance-status — 修改字段治理状态

**Request**:
```json
{
  "governanceStatus": "SENSITIVE",
  "remark": "该字段包含用户手机号，标记为敏感"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "tableName": "users",
    "columnName": "phone",
    "oldStatus": "DISCOVERED",
    "newStatus": "SENSITIVE"
  }
}
```

---

### PATCH /api/admin/snapshots/{snapshotId}/tables/{tableName}/batch-governance-status — 批量修改表下所有字段治理状态

**Request**:
```json
{
  "governanceStatus": "NORMAL",
  "remark": "批量确认该表所有字段可用",
  "excludeColumns": ["password", "secret_key"]
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "updated": 15, "excluded": 2 }
}
```

---

## Quality Rule APIs

### GET /api/admin/quality-rules — 质量规则列表

**Response** (200 OK):
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "ruleCode": "COMP_TABLE_COMMENT_MISSING",
      "ruleName": "表注释缺失",
      "dimension": "COMPLETENESS",
      "severity": "HIGH",
      "description": "检测表是否缺少注释",
      "enabled": true,
      "deductionPoints": 5.0,
      "builtin": true
    }
  ]
}
```

---

### PATCH /api/admin/quality-rules/{ruleId} — 启用/禁用规则

**Request**:
```json
{
  "enabled": false
}
```

---

## Review Record APIs

### GET /api/admin/snapshots/{snapshotId}/review-records — 审核记录

**Query Params**: `page=1&size=20&tableName=orders`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "targetType": "COLUMN",
        "tableName": "orders",
        "columnName": "status",
        "action": "STATUS_CHANGE",
        "oldStatus": "DISCOVERED",
        "newStatus": "NORMAL",
        "operatorName": "admin",
        "remark": "确认可用",
        "createdAt": "2026-05-16T11:30:00"
      }
    ],
    "total": 10,
    "page": 1,
    "size": 20
  }
}
```

---

## Authentication

所有接口需要 JWT Token + ADMIN 角色。
