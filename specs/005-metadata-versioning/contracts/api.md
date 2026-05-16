# API Contracts: 元数据版本与审核模块

**Date**: 2026-05-16 | **Base Path**: `/api/admin`

## Snapshot Lifecycle APIs

### PATCH /api/admin/snapshots/{snapshotId}/status — 快照状态流转

**Description**: 推进快照状态到下一阶段

**Request**:
```json
{
  "targetStatus": "PUBLISHED",
  "reason": "质量校验通过，确认发布"
}
```

**Valid Transitions**:
- DRAFT → CHECKING
- ISSUE_FOUND → APPROVED (需所有 HIGH 问题已处理)
- APPROVED → PUBLISHED (需至少一张表为 NORMAL/RECOMMENDED)
- PUBLISHED → APPROVED (紧急撤回，reason 必填)

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "snapshotId": 10,
    "oldStatus": "APPROVED",
    "newStatus": "PUBLISHED",
    "publishedAt": "2026-05-16T14:00:00",
    "previousPublishedSnapshotId": 8,
    "message": "发布成功，旧版本(v2)已自动过期"
  }
}
```

**Errors**:
- 400: 非法状态流转（如 DRAFT → PUBLISHED）
- 409: 存在未解决的 HIGH 问题，无法发布
- 409: 快照中无 NORMAL/RECOMMENDED 状态的表

**Error Response Example**:
```json
{
  "code": 409,
  "message": "无法发布：存在 3 个未解决的高风险问题",
  "data": {
    "unresolvedHighIssues": 3,
    "issueIds": [101, 105, 108]
  }
}
```

---

### GET /api/admin/datasources/{datasourceId}/version-history — 版本历史

**Query Params**: `page=1&size=10`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "snapshotId": 10,
        "snapshotVersion": 3,
        "status": "PUBLISHED",
        "tableCount": 150,
        "columnCount": 2340,
        "qualityScore": 85.5,
        "schemaHash": "a1b2c3d4...",
        "createdAt": "2026-05-16T10:00:45",
        "publishedAt": "2026-05-16T14:00:00",
        "reviewedBy": "admin"
      },
      {
        "snapshotId": 8,
        "snapshotVersion": 2,
        "status": "EXPIRED",
        "tableCount": 148,
        "columnCount": 2300,
        "qualityScore": 78.0,
        "schemaHash": "b2c3d4e5...",
        "createdAt": "2026-05-10T10:00:00",
        "publishedAt": "2026-05-10T15:00:00",
        "expiredAt": "2026-05-16T14:00:00",
        "reviewedBy": "admin"
      }
    ],
    "total": 3,
    "page": 1,
    "size": 10
  }
}
```

---

### GET /api/admin/datasources/{datasourceId}/published-snapshot — 获取当前已发布快照

**Description**: 获取指定数据源当前唯一的 PUBLISHED 快照

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "snapshotId": 10,
    "snapshotVersion": 3,
    "status": "PUBLISHED",
    "tableCount": 150,
    "columnCount": 2340,
    "qualityScore": 85.5,
    "publishedAt": "2026-05-16T14:00:00"
  }
}
```

**Response** (200 OK, 无已发布快照):
```json
{
  "code": 200,
  "data": null,
  "message": "该数据源暂无已发布的快照"
}
```

---

### GET /api/admin/snapshots/{snapshotId}/diff/{compareSnapshotId} — 版本对比

**Description**: 对比两个快照之间的差异（复用 003 模块接口，此处为便捷入口）

**Response**: 同 003 模块的 diff 接口响应格式

---

### GET /api/admin/snapshots/{snapshotId}/audit-logs — 快照操作日志

**Query Params**: `page=1&size=20`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "action": "PUBLISH",
        "oldStatus": "APPROVED",
        "newStatus": "PUBLISHED",
        "operatorName": "admin",
        "reason": "质量校验通过，确认发布",
        "createdAt": "2026-05-16T14:00:00"
      },
      {
        "id": 2,
        "action": "STATUS_TRANSITION",
        "oldStatus": "CHECKING",
        "newStatus": "APPROVED",
        "operatorName": "system",
        "reason": "质量校验完成，无高风险问题",
        "createdAt": "2026-05-16T13:50:00"
      }
    ],
    "total": 5,
    "page": 1,
    "size": 20
  }
}
```

---

### GET /api/admin/datasources/{datasourceId}/audit-logs — 数据源级操作日志

**Query Params**: `page=1&size=20&action=PUBLISH`

**Response**: 同上格式，按数据源过滤

---

## Internal APIs (供其他模块调用)

### GET /api/internal/datasources/{datasourceId}/published-snapshot-id — 获取已发布快照 ID

**Description**: 供 skills.md 生成模块和 RAG 模块内部调用，确认当前可用快照

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "snapshotId": 10 }
}
```

**Response** (404):
```json
{
  "code": 404,
  "message": "该数据源无已发布快照"
}
```

---

## Authentication

- `/api/admin/**` 需要 JWT Token + ADMIN 角色
- `/api/internal/**` 需要内部服务认证（Service Token 或 IP 白名单）
