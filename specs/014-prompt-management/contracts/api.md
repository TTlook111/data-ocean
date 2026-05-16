# API Contracts: Prompt 管理模块

## Base URL

- 管理端: `/api/admin/prompt-templates`
- Python 内部: `/internal/prompts`

## Authentication

管理端接口需要 JWT Token + `prompt:manage` 权限。
内部接口通过服务间认证（X-Internal-Token header）。

---

## GET /api/admin/prompt-templates

获取所有 Prompt 模板列表。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "templateCode": "sql_generation",
      "templateName": "SQL 生成",
      "scenario": "query",
      "currentVersion": 3,
      "enabled": true,
      "updatedAt": "2026-05-15T14:30:00"
    }
  ]
}
```

---

## GET /api/admin/prompt-templates/{id}

获取模板详情（含当前活跃版本内容）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "templateCode": "sql_generation",
    "templateName": "SQL 生成",
    "scenario": "query",
    "content": "你是一个 SQL 专家...\n{{schema}}\n{{question}}",
    "currentVersion": 3,
    "enabled": true,
    "updatedAt": "2026-05-15T14:30:00"
  }
}
```

---

## PUT /api/admin/prompt-templates/{id}

更新模板内容（自动创建新版本）。

**Request**:
```json
{
  "content": "你是一个专业的 SQL 生成助手...\n{{schema}}\n{{skills_md}}\n{{question}}",
  "changeSummary": "优化 system prompt，增加 skills_md 变量"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "newVersion": 4
  },
  "message": "保存成功，已创建版本 v4"
}
```

**Response 409** (乐观锁冲突):
```json
{
  "code": 409,
  "message": "模板已被其他人修改，请刷新后重试"
}
```

---

## GET /api/admin/prompt-templates/{id}/versions

获取模板的版本历史列表。

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 10,
      "versionNo": 3,
      "changeSummary": "增加 few-shot 示例",
      "createdBy": "admin",
      "createdAt": "2026-05-15T14:30:00",
      "isActive": true
    },
    {
      "id": 9,
      "versionNo": 2,
      "changeSummary": "调整 system prompt 语气",
      "createdBy": "admin",
      "createdAt": "2026-05-14T10:00:00",
      "isActive": false
    }
  ]
}
```

---

## GET /api/admin/prompt-templates/{id}/versions/{versionNo}

获取指定版本的完整内容（用于对比和预览）。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "versionNo": 2,
    "content": "你是一个 SQL 专家...",
    "changeSummary": "调整 system prompt 语气",
    "createdBy": "admin",
    "createdAt": "2026-05-14T10:00:00"
  }
}
```

---

## POST /api/admin/prompt-templates/{id}/rollback

回滚到指定版本。

**Request**:
```json
{
  "targetVersion": 2
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "已回滚到版本 v2"
}
```

**Response 400** (版本不存在):
```json
{
  "code": 400,
  "message": "版本 v5 不存在"
}
```

---

## POST /api/admin/prompt-templates/{id}/preview

预览模板渲染结果（填入示例变量）。

**Request**:
```json
{
  "variables": {
    "question": "查询上月销售额",
    "schema": "CREATE TABLE orders (id INT, amount DECIMAL, created_at DATE)",
    "skills_md": "- pay_amount: 实际支付金额"
  }
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "renderedContent": "你是一个专业的 SQL 生成助手...\nCREATE TABLE orders...\n查询上月销售额",
    "tokenCount": 856
  }
}
```

---

## Python Internal API

### GET /internal/prompts/{template_code}

Python 服务获取当前活跃版本的模板内容。

**Path Parameters**:
- `template_code`: 模板编码 (sql_generation, chart_generation 等)

**Response 200**:
```json
{
  "template_code": "sql_generation",
  "content": "你是一个专业的 SQL 生成助手...\n{{schema}}\n{{skills_md}}\n{{question}}",
  "version": 3
}
```

**Response 404** (模板不存在或已禁用):
```json
{
  "error": "template_not_found",
  "message": "Template sql_generation not found or disabled"
}
```
