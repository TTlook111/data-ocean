# API Contracts: 后台治理管理端模块

后台管理端消费其他模块提供的 API。本文档定义后台特有的聚合接口（Dashboard 统计等）。

## Base URL

- 管理端: `/api/admin/*`

## Authentication

所有接口需要 JWT Token + 对应权限。

---

## Dashboard API

### GET /api/admin/dashboard/stats

获取首页看板统计数据。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "totalDatasources": 5,
    "totalQueries": 12500,
    "todayQueries": 85,
    "successRate": 92.5,
    "pendingFeedbacks": 3,
    "pendingIssues": 7,
    "slowQueries": 2,
    "avgResponseTimeMs": 2800
  }
}
```

---

### GET /api/admin/dashboard/query-trend

获取查询趋势数据。

**Query Parameters**:
- `days` (int, default 7) — 最近 N 天

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "dates": ["2026-05-10", "2026-05-11", "2026-05-12"],
    "totalCounts": [120, 135, 98],
    "successCounts": [110, 128, 90],
    "failCounts": [10, 7, 8]
  }
}
```

---

### GET /api/admin/dashboard/quality-scores

获取各数据源质量分。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "datasourceId": 1,
      "datasourceName": "订单库",
      "score": 85,
      "dimensions": {
        "completeness": 90,
        "accuracy": 80,
        "consistency": 85,
        "timeliness": 85
      }
    }
  ]
}
```

---

## Consumed APIs (from other modules)

后台管理端消费的 API 列表（详细契约见各模块 contracts/api.md）:

### 用户管理 (001-user-module)
- POST /api/auth/login
- GET /api/auth/me
- POST /api/auth/logout
- GET /api/admin/users
- POST /api/admin/users
- PUT /api/admin/users/{id}
- PATCH /api/admin/users/{id}/status
- DELETE /api/admin/users/{id}
- GET /api/admin/roles
- POST /api/admin/roles
- PUT /api/admin/roles/{id}

### 数据源管理 (003-datasource)
- GET /api/admin/datasources
- POST /api/admin/datasources
- PUT /api/admin/datasources/{id}
- DELETE /api/admin/datasources/{id}
- POST /api/admin/datasources/{id}/test-connection

### 元数据管理 (005-metadata)
- GET /api/admin/metadata/datasources/{id}/tables
- GET /api/admin/metadata/tables/{id}/columns
- POST /api/admin/metadata/datasources/{id}/sync
- GET /api/admin/metadata/snapshots

### 治理管理 (005-metadata)
- GET /api/admin/governance/issues
- PUT /api/admin/governance/issues/{id}
- GET /api/admin/governance/status

### Skills 管理 (007-skills)
- GET /api/admin/skills/datasources/{id}
- PUT /api/admin/skills/datasources/{id}
- POST /api/admin/skills/datasources/{id}/publish
- GET /api/admin/skills/datasources/{id}/versions

### 字段 Tag 与可信度 (010-field-tag-confidence)
- GET /api/admin/field-tags
- POST /api/admin/field-tags
- POST /api/admin/field-tags/batch
- DELETE /api/admin/field-tags/{id}
- GET /api/admin/field-confidence
- PUT /api/admin/field-confidence/{columnMetaId}
- GET /api/admin/feedback-reviews
- POST /api/admin/feedback-reviews/{feedbackId}

### 审计日志 (011-lineage-audit)
- GET /api/admin/audit-logs
- GET /api/admin/audit-logs/{id}
- GET /api/admin/audit-logs/slow-queries
- POST /api/admin/audit-logs/export
- GET /api/admin/lineage/table/{tableMetaId}
- GET /api/admin/lineage/column/{columnMetaId}
