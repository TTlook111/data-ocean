# API Contracts: 数据源管理模块

**Date**: 2026-05-16 | **Base Path**: `/api`

## Admin APIs (需要 ADMIN 角色)

### POST /api/admin/datasources — 创建数据源

**Request**:
```json
{
  "name": "订单库-生产",
  "description": "电商订单主库",
  "host": "192.168.1.100",
  "port": 3306,
  "databaseName": "order_db",
  "charset": "utf8mb4",
  "username": "readonly_user",
  "password": "plain_text_password"
}
```

**Response** (201 Created):
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "name": "订单库-生产",
    "description": "电商订单主库",
    "dbType": "MYSQL",
    "host": "192.168.1.100",
    "port": 3306,
    "databaseName": "order_db",
    "charset": "utf8mb4",
    "status": 1,
    "username": "readonly_user",
    "createdAt": "2026-05-16T10:00:00"
  }
}
```

**Errors**:
- 400: 参数校验失败
- 409: 相同 host+port+database 已存在（阻止保存）

---

### PUT /api/admin/datasources/{id} — 更新数据源

**Request**:
```json
{
  "name": "订单库-生产(更新)",
  "description": "更新描述",
  "host": "192.168.1.100",
  "port": 3306,
  "databaseName": "order_db",
  "charset": "utf8mb4",
  "username": "readonly_user",
  "password": "new_password_or_null_to_keep"
}
```

**Response** (200 OK): 同创建响应格式

**Notes**: password 为 null 时保持原密码不变

---

### DELETE /api/admin/datasources/{id} — 删除数据源（软删除）

**Response** (200 OK):
```json
{
  "code": 200,
  "data": null,
  "message": "删除成功"
}
```

**Errors**:
- 409: 数据源存在未过期的快照或 skills.md 依赖，无法删除

---

### POST /api/admin/datasources/test-connection — 测试连接

**Request**:
```json
{
  "host": "192.168.1.100",
  "port": 3306,
  "databaseName": "order_db",
  "username": "readonly_user",
  "password": "plain_text_password"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "success": true,
    "responseTimeMs": 45,
    "serverVersion": "8.0.32",
    "message": "连接成功"
  }
}
```

**Response** (200 OK, 连接失败):
```json
{
  "code": 200,
  "data": {
    "success": false,
    "responseTimeMs": 5000,
    "serverVersion": null,
    "message": "连接超时：无法连接到 192.168.1.100:3306"
  }
}
```

---

### POST /api/admin/datasources/{id}/test-connection — 测试已保存数据源的连接

**Request**: 无 body（使用已存储的凭证）

**Response**: 同上

---

### PATCH /api/admin/datasources/{id}/status — 启用/禁用数据源

**Request**:
```json
{
  "status": 0
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "id": 1, "status": 0 }
}
```

---

### GET /api/admin/datasources — 管理端数据源列表（分页）

**Query Params**: `page=1&size=20&name=订单&status=1`

**Response** (200 OK):
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "name": "订单库-生产",
        "dbType": "MYSQL",
        "host": "192.168.1.100",
        "port": 3306,
        "databaseName": "order_db",
        "status": 1,
        "lastCheckSuccess": true,
        "lastCheckTime": "2026-05-16T09:00:00",
        "createdAt": "2026-05-15T10:00:00"
      }
    ],
    "total": 15,
    "page": 1,
    "size": 20
  }
}
```

---

### POST /api/admin/datasources/{id}/access — 授权用户访问

**Request**:
```json
{
  "userIds": [2, 3, 5],
  "expiresAt": "2027-01-01T00:00:00"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "data": { "granted": 3 }
}
```

---

### DELETE /api/admin/datasources/{id}/access/{userId} — 撤销用户访问权限

**Response** (200 OK)

---

## User APIs (需要登录)

### GET /api/datasources — 用户可用数据源列表

**Description**: 返回当前用户有权限访问的已启用数据源（精简信息）

**Response** (200 OK):
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "订单库-生产",
      "databaseName": "order_db",
      "description": "电商订单主库"
    },
    {
      "id": 3,
      "name": "用户库-生产",
      "databaseName": "user_db",
      "description": "用户中心数据库"
    }
  ]
}
```

**Notes**: 不返回 host/port/username 等敏感信息

---

## Common Error Response Format

```json
{
  "code": 400,
  "message": "参数校验失败",
  "errors": [
    { "field": "host", "message": "不能为空" }
  ]
}
```

## Authentication

所有接口需要 JWT Token（Header: `Authorization: Bearer <token>`）。
- `/api/admin/**` 需要 ADMIN 角色
- `/api/datasources` 需要登录即可
