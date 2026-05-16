# API Contracts: 字段 Tag 与可信度模块

## Base URL

- 管理端: `/api/admin/field-tags/*`, `/api/admin/field-confidence/*`, `/api/admin/feedback-reviews/*`
- 用户端: `/api/feedback/*`

## Authentication

所有接口需要 JWT Token（Header: `Authorization: Bearer {token}`）。
管理端接口需要 `field:manage` 或 `feedback:review` 权限。

---

## 字段标签 API

### GET /api/admin/field-tags

查询字段标签列表。

**Query Parameters**:
- `columnMetaId` (long, optional) — 按字段筛选
- `tableMetaId` (long, optional) — 按表筛选（返回该表所有字段的标签）
- `tagCode` (string, optional) — 按标签类型筛选
- `datasourceId` (long, optional) — 按数据源筛选

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "columnMetaId": 101,
      "columnName": "order_amount",
      "tableName": "order_info",
      "tagCode": "AMOUNT",
      "tagName": "金额类",
      "source": "MANUAL",
      "createdBy": "admin",
      "createdAt": "2026-05-10T10:00:00"
    }
  ]
}
```

---

### POST /api/admin/field-tags

为字段添加标签。

**Request**:
```json
{
  "columnMetaId": 101,
  "tagCode": "AMOUNT"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "id": 1 },
  "message": "标签添加成功"
}
```

**Response 409** (重复标签):
```json
{
  "code": 409,
  "message": "该字段已存在此标签"
}
```

---

### POST /api/admin/field-tags/batch

批量为字段打标签。

**Request**:
```json
{
  "columnMetaIds": [101, 102, 103],
  "tagCode": "TIME"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "successCount": 3, "skipCount": 0 },
  "message": "批量打标完成"
}
```

---

### DELETE /api/admin/field-tags/{id}

删除字段标签。

**Response 200**:
```json
{
  "code": 200,
  "message": "标签删除成功"
}
```

---

## 字段可信度 API

### GET /api/admin/field-confidence

查询字段可信度列表。

**Query Parameters**:
- `tableMetaId` (long, optional) — 按表筛选
- `datasourceId` (long, optional) — 按数据源筛选
- `level` (string, optional) — HIGH/MEDIUM/LOW
- `page` (int, default 1)
- `pageSize` (int, default 20)

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "columnMetaId": 101,
        "columnName": "order_amount",
        "tableName": "order_info",
        "score": 60,
        "level": "MEDIUM",
        "source": "SKILLS",
        "reason": "skills.md 中已定义",
        "updatedAt": "2026-05-10T10:00:00"
      }
    ],
    "total": 150,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### PUT /api/admin/field-confidence/{columnMetaId}

管理员手动设置可信度分数。

**Request**:
```json
{
  "score": 90,
  "reason": "人工确认字段含义准确"
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "可信度更新成功"
}
```

---

### GET /api/admin/field-confidence/{columnMetaId}/events

查询字段可信度变更历史。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "previousScore": 30,
      "newScore": 60,
      "deltaScore": 30,
      "eventType": "SKILLS_PUBLISH",
      "operatorName": "system",
      "remark": "skills.md v2 发布",
      "createdAt": "2026-05-10T10:00:00"
    }
  ]
}
```

---

## 用户反馈 API

### POST /api/feedback

用户提交查询反馈。

**Request**:
```json
{
  "queryTaskId": 1001,
  "feedbackType": "THUMBS_DOWN",
  "reasonCode": "DATA_INACCURATE",
  "comment": "订单金额数据不对",
  "relatedColumnIds": [101, 102]
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "反馈提交成功"
}
```

**Response 429** (限频):
```json
{
  "code": 429,
  "message": "今日已对该字段提交过负向反馈，请明天再试"
}
```

---

## 反馈审核 API

### GET /api/admin/feedback-reviews

查询待审核反馈列表。

**Query Parameters**:
- `status` (string, optional) — PENDING/APPROVED/REJECTED, default PENDING
- `page` (int, default 1)
- `pageSize` (int, default 20)

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "feedbackId": 1,
        "queryTaskId": 1001,
        "username": "zhangsan",
        "feedbackType": "THUMBS_DOWN",
        "reasonCode": "DATA_INACCURATE",
        "comment": "订单金额数据不对",
        "relatedColumns": [
          { "columnMetaId": 101, "columnName": "order_amount", "tableName": "order_info" }
        ],
        "reviewStatus": "PENDING",
        "createdAt": "2026-05-15T14:30:00"
      }
    ],
    "total": 5,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### POST /api/admin/feedback-reviews/{feedbackId}

审核反馈。

**Request**:
```json
{
  "reviewStatus": "APPROVED",
  "reviewComment": "确认数据确实有误"
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "审核完成，可信度已调整"
}
```
