# Data Model: 用户模块

## Entity Relationship

```
sys_department (1) ──< sys_user (N)
sys_user (N) >──< sys_role (M)  [via sys_user_role]
sys_role (N) >──< sys_permission (M)  [via sys_role_permission]
```

## Tables

### sys_user

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 用户ID |
| username | VARCHAR(50) | UNIQUE, NOT NULL | 登录用户名 |
| password_hash | VARCHAR(100) | NOT NULL | BCrypt 加密密码 |
| real_name | VARCHAR(50) | NOT NULL | 真实姓名 |
| email | VARCHAR(100) | | 邮箱 |
| phone | VARCHAR(20) | | 手机号 |
| department_id | BIGINT | FK → sys_department.id | 所属部门 |
| status | TINYINT | NOT NULL, DEFAULT 1 | 1=正常, 2=禁用, 3=锁定 |
| last_login_at | DATETIME | | 最后登录时间 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |
| password_changed | TINYINT | NOT NULL, DEFAULT 0 | 是否已修改初始密码（0=未修改，1=已修改） |
| deleted | TINYINT | NOT NULL, DEFAULT 0 | 逻辑删除标记 |

### sys_role

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 角色ID |
| role_code | VARCHAR(50) | UNIQUE, NOT NULL | 角色编码 (ADMIN/ANALYST/DATA_MANAGER/SECURITY_MANAGER/USER) |
| role_name | VARCHAR(50) | NOT NULL | 角色名称 |
| description | VARCHAR(200) | | 角色描述 |
| status | TINYINT | NOT NULL, DEFAULT 1 | 1=启用, 0=禁用 |
| created_at | DATETIME | NOT NULL | 创建时间 |

### sys_department

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 部门ID |
| parent_id | BIGINT | FK → sys_department.id, NULLABLE | 上级部门 |
| dept_name | VARCHAR(50) | NOT NULL | 部门名称 |
| dept_code | VARCHAR(50) | UNIQUE, NOT NULL | 部门编码 |
| sort_order | INT | DEFAULT 0 | 排序 |
| status | TINYINT | NOT NULL, DEFAULT 1 | 1=启用, 0=禁用 |
| created_at | DATETIME | NOT NULL | 创建时间 |

### sys_user_role

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 关联ID |
| user_id | BIGINT | FK → sys_user.id, NOT NULL | 用户ID |
| role_id | BIGINT | FK → sys_role.id, NOT NULL | 角色ID |

UNIQUE INDEX: (user_id, role_id)

### sys_permission

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 权限ID |
| permission_code | VARCHAR(100) | UNIQUE, NOT NULL | 权限编码 (如 user:create, datasource:manage) |
| permission_name | VARCHAR(100) | NOT NULL | 权限名称 |
| module | VARCHAR(50) | NOT NULL | 所属模块 |
| description | VARCHAR(200) | | 描述 |

### sys_role_permission

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 关联ID |
| role_id | BIGINT | FK → sys_role.id, NOT NULL | 角色ID |
| permission_id | BIGINT | FK → sys_permission.id, NOT NULL | 权限ID |

UNIQUE INDEX: (role_id, permission_id)

## State Transitions

### User Status

```
NORMAL (1) ──[管理员禁用]──> DISABLED (2)
NORMAL (1) ──[连续5次登录失败]──> LOCKED (3)
DISABLED (2) ──[管理员启用]──> NORMAL (1)
LOCKED (3) ──[管理员解锁]──> NORMAL (1)
```

## Validation Rules

- username: 4-50 字符，字母数字下划线，不可重复
- password: 8-32 字符，至少包含字母和数字
- real_name: 2-50 字符
- email: 标准邮箱格式（可选）
- department_id: 必须引用已存在且启用的部门
- role_id: 必须引用已存在且启用的角色
