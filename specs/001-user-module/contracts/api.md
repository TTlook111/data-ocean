# API Contracts: 用户模块

## Base URL

- 用户端: `/api/auth/*`
- 管理端: `/api/admin/users/*`, `/api/admin/roles/*`, `/api/admin/departments/*`

## Authentication

所有 `/api/admin/*` 接口需要 JWT Token（Header: `Authorization: Bearer {token}`）。
`/api/auth/login` 不需要认证。

---

## POST /api/auth/login

登录并获取 JWT Token。

**Request**:
```json
{
  "username": "admin",
  "password": "password123"
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": 1,
      "username": "admin",
      "realName": "超级管理员",
      "roles": ["ADMIN"],
      "permissions": ["*"]
    }
  }
}
```

**Response 401** (密码错误):
```json
{
  "code": 401,
  "message": "用户名或密码错误"
}
```

**Response 403** (账号禁用/锁定):
```json
{
  "code": 403,
  "message": "账号已被禁用"
}
```

---

## GET /api/auth/me

获取当前登录用户信息。

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "username": "admin",
    "realName": "超级管理员",
    "email": "admin@example.com",
    "department": { "id": 1, "deptName": "技术部" },
    "roles": [{ "id": 1, "roleCode": "ADMIN", "roleName": "超级管理员" }],
    "permissions": ["*"]
  }
}
```

---

## POST /api/auth/logout

退出登录，Token 加入黑名单。

**Response 200**:
```json
{
  "code": 200,
  "message": "退出成功"
}
```

---

## GET /api/admin/users

分页查询用户列表。

**Query Parameters**:
- `page` (int, default 1)
- `pageSize` (int, default 20, max 100)
- `username` (string, optional) — 模糊匹配
- `realName` (string, optional) — 模糊匹配
- `departmentId` (long, optional)
- `status` (int, optional) — 1/2/3

**Response 200**:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 2,
        "username": "zhangsan",
        "realName": "张三",
        "email": "zhangsan@example.com",
        "department": { "id": 2, "deptName": "数据部" },
        "roles": [{ "roleCode": "ANALYST", "roleName": "数据分析师" }],
        "status": 1,
        "lastLoginAt": "2026-05-15T10:30:00",
        "createdAt": "2026-05-01T09:00:00"
      }
    ],
    "total": 50,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## POST /api/admin/users

创建用户。

**Request**:
```json
{
  "username": "zhangsan",
  "password": "Pass1234",
  "realName": "张三",
  "email": "zhangsan@example.com",
  "phone": "13800138000",
  "departmentId": 2,
  "roleIds": [2, 3]
}
```

**Response 200**:
```json
{
  "code": 200,
  "data": { "id": 10 },
  "message": "创建成功"
}
```

**Response 400** (用户名重复):
```json
{
  "code": 400,
  "message": "用户名已存在"
}
```

---

## PUT /api/admin/users/{id}

更新用户信息。

**Request**:
```json
{
  "realName": "张三丰",
  "email": "zhangsanfeng@example.com",
  "departmentId": 3,
  "roleIds": [2]
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

## PATCH /api/admin/users/{id}/status

修改用户状态（启用/禁用/解锁）。

**Request**:
```json
{
  "status": 2
}
```

**Response 200**:
```json
{
  "code": 200,
  "message": "状态更新成功"
}
```

---

## DELETE /api/admin/users/{id}

删除用户（逻辑删除）。

**Response 200**:
```json
{
  "code": 200,
  "message": "删除成功"
}
```

**Response 400** (尝试删除超级管理员):
```json
{
  "code": 400,
  "message": "不允许删除超级管理员"
}
```
