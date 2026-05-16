# API Contracts: 权限与安全模块

## Base URL

- 管理端: `/api/admin/datasource-access`, `/api/admin/access-policies`

## Authentication

所有接口需要 JWT Token + `security:manage` 权限（SECURITY_MANAGER 或 ADMIN 角色）。

---

## GET /api/admin/datasource-access

查询数据源访问授权列表。

**Query Parameters**:
- `datasourceId` (long, required) — 数据源ID
- `subjectType` (string, optional) — USER/ROLE/DEPARTMENT

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "datasourceId": 10,
      "subjectType": "ROLE",
      "subjectId": 2,
      "subjectName": "数据分析师",
      "canQuery": true,
      "canExport": true,
      "canViewSql": true,
      "createdAt": "2026-05-10T09:00:00"
    }
  ]
}
```

---

## POST /api/admin/datasource-access

创建数据源访问授权。

**Request**:
```json
{
  "datasourceId": 10,
  "subjectType": "ROLE",
  "subjectId": 2,
  "canQuery": true,
  "canExport": true,
  "canViewSql": true
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "id": 5 },
  "message": "授权成功"
}
```

**Response 400** (重复授权):
```json
{
  "code": 400,
  "message": "该主体已有此数据源的授权记录"
}
```

---

## PUT /api/admin/datasource-access/{id}

更新数据源访问权限。

**Request**:
```json
{
  "canQuery": true,
  "canExport": false,
  "canViewSql": true
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "更新成功"
}
```

---

## DELETE /api/admin/datasource-access/{id}

删除数据源访问授权。

**Response 200**:
```json
{
  "code": 200,
  "message": "已取消授权"
}
```

---

## GET /api/admin/access-policies

查询行列级访问策略列表。

**Query Parameters**:
- `datasourceId` (long, required) — 数据源ID
- `subjectType` (string, optional) — USER/ROLE/DEPARTMENT
- `subjectId` (long, optional)
- `tableName` (string, optional)

**Response 200**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "datasourceId": 10,
      "subjectType": "ROLE",
      "subjectId": 3,
      "subjectName": "普通员工",
      "tableName": "customers",
      "columnName": "phone",
      "accessType": "MASK",
      "maskStrategy": "PHONE",
      "rowFilterExpression": null,
      "createdAt": "2026-05-10T09:00:00"
    },
    {
      "id": 2,
      "datasourceId": 10,
      "subjectType": "ROLE",
      "subjectId": 3,
      "subjectName": "普通员工",
      "tableName": "orders",
      "columnName": null,
      "accessType": "ALLOW",
      "maskStrategy": null,
      "rowFilterExpression": "region = '华东'",
      "createdAt": "2026-05-10T09:00:00"
    }
  ]
}
```

---

## POST /api/admin/access-policies

创建行列级访问策略。

**Request**:
```json
{
  "datasourceId": 10,
  "subjectType": "ROLE",
  "subjectId": 3,
  "tableName": "customers",
  "columnName": "phone",
  "accessType": "MASK",
  "maskStrategy": "PHONE"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "id": 10 },
  "message": "策略创建成功"
}
```

---

## POST /api/admin/access-policies/batch

批量创建策略（同一主体、同一表的多列配置）。

**Request**:
```json
{
  "datasourceId": 10,
  "subjectType": "ROLE",
  "subjectId": 3,
  "tableName": "customers",
  "policies": [
    { "columnName": "phone", "accessType": "MASK", "maskStrategy": "PHONE" },
    { "columnName": "id_card", "accessType": "DENY" },
    { "columnName": "bank_account", "accessType": "DENY" }
  ]
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "批量创建成功，共 3 条策略"
}
```

---

## PUT /api/admin/access-policies/{id}

更新策略。

**Request**:
```json
{
  "accessType": "DENY",
  "maskStrategy": null
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "策略更新成功"
}
```

---

## DELETE /api/admin/access-policies/{id}

删除策略。

**Response 200**:
```json
{
  "code": 200,
  "message": "策略已删除"
}
```

---

## Internal: Permission Context in Query Request

Java 调用 Python 查询接口时，在请求体中附带权限上下文：

```json
{
  "question": "查询上月华东区销售额",
  "datasourceId": 10,
  "permissionContext": {
    "allowedTables": ["orders", "customers", "products"],
    "deniedColumns": {
      "customers": ["id_card", "bank_account"]
    },
    "maskColumns": {
      "customers": {"phone": "PHONE", "email": "EMAIL"}
    },
    "rowFilters": {
      "orders": "region = '华东'"
    }
  }
}
```

Python 在 SQL_Validator_Node 中使用 permissionContext 执行 AST 注入，确保生成的 SQL 符合权限约束。
