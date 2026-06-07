# API Contracts: skills.md 业务知识库模块

## Base URL

- 管理端: `/api/admin/knowledge-docs/*`
- Python 内部: `/internal/knowledge/*`

## Authentication

所有 `/api/admin/*` 接口需要 JWT Token，角色要求: ADMIN 或 ANALYST。

---

## GET /api/admin/knowledge-docs

获取知识文档列表（按数据源）。

**Query Parameters**:
- `datasourceId` (long, required)
- `status` (string, optional) — DRAFT/PENDING_REVIEW/APPROVED/INDEXING/PUBLISHED/DEPRECATED

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "datasourceId": 10,
    "title": "订单库 skills.md",
    "currentVersionNo": 3,
    "status": "PUBLISHED",
    "updatedBy": "张三",
    "updatedAt": "2026-05-15T14:00:00"
  }
}
```

---

## GET /api/admin/knowledge-docs/{id}

获取文档详情（含当前版本内容）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "datasourceId": 10,
    "title": "订单库 skills.md",
    "currentVersionNo": 3,
    "status": "PUBLISHED",
    "currentVersion": {
      "versionNo": 3,
      "content": "## 文档来源\n...",
      "metadataSnapshotId": 5,
      "generationSource": "MANUAL",
      "reviewStatus": "APPROVED",
      "createdBy": "张三",
      "createdAt": "2026-05-14T10:00:00"
    }
  }
}
```

---

## GET /api/admin/knowledge-docs/{id}/versions

获取版本历史列表。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "versionNo": 3,
      "generationSource": "MANUAL",
      "reviewStatus": "APPROVED",
      "changeSummary": "补充退款指标口径",
      "createdBy": "张三",
      "createdAt": "2026-05-14T10:00:00"
    },
    {
      "versionNo": 2,
      "generationSource": "AI_GENERATED",
      "reviewStatus": "APPROVED",
      "changeSummary": "AI 生成初始草稿",
      "createdBy": "system",
      "createdAt": "2026-05-10T09:00:00"
    }
  ]
}
```

---

## PUT /api/admin/knowledge-docs/{id}

编辑文档内容（创建新版本）。

**Request**:
```json
{
  "content": "## 文档来源\n...(updated markdown)",
  "changeSummary": "补充退款指标口径",
  "version": 3
}
```

`version` 为乐观锁字段，从 GET 详情获取。

**Response 200**:
```json
{
  "code": 200,
  "data": { "versionNo": 4 },
  "message": "保存成功"
}
```

**Response 409** (乐观锁冲突):
```json
{
  "code": 409,
  "message": "内容已被他人修改，请刷新后重试"
}
```

---

## POST /api/admin/knowledge-docs/{id}/generate-draft

基于元数据快照生成 AI 草稿。

**Request**:
```json
{
  "metadataSnapshotId": 5
}
```

**Response 202**:
```json
{
  "code": 202,
  "data": { "versionNo": 1 },
  "message": "草稿生成中，请稍候"
}
```

**Response 400** (快照不存在或未发布):
```json
{
  "code": 400,
  "message": "元数据快照不存在或未发布"
}
```

---

## POST /api/admin/knowledge-docs/{id}/submit-review

提交审核。

**Response 200**:
```json
{
  "code": 200,
  "message": "已提交审核"
}
```

---

## POST /api/admin/knowledge-docs/{id}/review

审核通过/拒绝。

**Request**:
```json
{
  "action": "APPROVE",
  "comment": "内容准确，通过"
}
```

action: APPROVE / REJECT

**Response 200**:
```json
{
  "code": 200,
  "message": "审核完成"
}
```

---

## POST /api/admin/knowledge-docs/{id}/publish

发布（触发向量化）。

**Response 200**:
```json
{
  "code": 200,
  "message": "发布成功，向量化任务已创建"
}
```

**Response 400** (校验失败):
```json
{
  "code": 400,
  "message": "发布校验失败",
  "data": {
    "errors": [
      "表 old_orders 在当前快照中不存在",
      "字段 orders.deprecated_col governance_status 为 DEPRECATED"
    ]
  }
}
```

---

## POST /api/admin/knowledge-docs/{id}/rollback

回滚到指定版本。

**Request**:
```json
{
  "targetVersionNo": 2
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "newVersionNo": 5 },
  "message": "回滚成功，已创建新版本"
}
```

---

## Python Internal API

### POST /internal/knowledge/generate-draft

Java 调用 Python 生成 skills.md 草稿。

**Request**:
```json
{
  "datasourceId": 10,
  "metadataSnapshotId": 5,
  "tables": [
    {
      "tableName": "orders",
      "tableComment": "订单主表",
      "columns": [
        {
          "columnName": "order_id",
          "columnType": "BIGINT",
          "comment": "订单ID",
          "isPrimaryKey": true,
          "trustScore": 95
        }
      ],
      "indexes": ["PRIMARY(order_id)", "idx_user_id(user_id)"],
      "foreignKeys": ["user_id → users.id"]
    }
  ]
}
```

**Response 200**:
```json
{
  "content": "## 文档来源\n\n- 数据源: 订单库\n- 快照版本: snapshot-5\n\n## 核心表说明\n...",
  "generationSource": "AI_GENERATED"
}
```

**Response 500** (LLM 调用失败):
```json
{
  "error": "LLM_CALL_FAILED",
  "message": "Qwen API 调用超时"
}
```
