# Data Model: 数据源管理模块

**Date**: 2026-05-16

## Tables

### datasource（数据源主表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| name | VARCHAR(100) | NO | | 数据源名称（显示用） |
| description | VARCHAR(500) | YES | NULL | 描述 |
| db_type | VARCHAR(20) | NO | 'MYSQL' | 数据库类型（MVP 仅 MYSQL） |
| host | VARCHAR(255) | NO | | 主机地址 |
| port | INT | NO | 3306 | 端口 |
| database_name | VARCHAR(100) | NO | | 数据库名 |
| charset | VARCHAR(20) | NO | 'utf8mb4' | 字符集 |
| status | TINYINT | NO | 1 | 1=启用, 0=禁用 |
| health_status | VARCHAR(20) | NO | 'UNKNOWN' | UNKNOWN/HEALTHY/UNHEALTHY，连接健康状态 |
| creator_id | BIGINT | NO | | 创建人 |
| deleted | BIGINT | NO | 0 | 逻辑删除，0=未删除，删除后写入本记录 id 以释放唯一键 |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_host_port_db` (host, port, database_name, deleted)
- INDEX `idx_status` (status)
- INDEX `idx_health_status` (health_status)

---

### datasource_secret（数据源密钥表，独立存储加密凭证）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 关联数据源 |
| username | VARCHAR(100) | NO | | 数据库用户名 |
| encrypted_password | VARCHAR(500) | NO | | AES-256-GCM 加密后的密码 |
| encrypt_version | INT | NO | 1 | 加密版本号（密钥轮换用） |
| created_at | DATETIME | NO | CURRENT_TIMESTAMP | |
| updated_at | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_datasource_id` (datasource_id)

**Design Notes**: 密码独立成表，避免主表查询时意外暴露加密字段。仅在需要建立连接时才查询此表。

---

### datasource_access（数据源访问权限表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 数据源 ID |
| user_id | BIGINT | NO | | 用户 ID |
| granted_by | BIGINT | NO | | 授权人 |
| granted_at | DATETIME | NO | CURRENT_TIMESTAMP | 授权时间 |
| expires_at | DATETIME | YES | NULL | 过期时间（NULL=永不过期） |

**Indexes**:
- PRIMARY KEY (id)
- UNIQUE INDEX `uk_ds_user` (datasource_id, user_id)
- INDEX `idx_user_id` (user_id)

---

### datasource_health_check（健康检查记录表）

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| id | BIGINT | NO | AUTO_INCREMENT | 主键 |
| datasource_id | BIGINT | NO | | 数据源 ID |
| check_type | VARCHAR(20) | NO | | MANUAL / SCHEDULED |
| success | TINYINT | NO | | 1=成功, 0=失败 |
| response_time_ms | INT | YES | NULL | 响应时间(ms) |
| error_message | VARCHAR(1000) | YES | NULL | 失败原因 |
| checked_at | DATETIME | NO | CURRENT_TIMESTAMP | 检查时间 |

**Indexes**:
- PRIMARY KEY (id)
- INDEX `idx_ds_time` (datasource_id, checked_at DESC)

## Relationships

```
datasource 1 ──── 1 datasource_secret
datasource 1 ──── N datasource_access
datasource 1 ──── N datasource_health_check
datasource 1 ──── N metadata_snapshot (003模块)
```

## State Transitions

### datasource.status

```
启用(1) ←→ 禁用(0)
```

- 启用 → 禁用: 管理员手动禁用，前台不可见，通知 Python 销毁连接池
- 禁用 → 启用: 管理员手动启用，需重新通过连接测试

### datasource.health_status

```
UNKNOWN → HEALTHY
UNKNOWN/HEALTHY → UNHEALTHY
UNHEALTHY → HEALTHY
```

- 手动或定时连接测试成功后标记为 HEALTHY
- 连续 3 次定时检测失败后标记为 UNHEALTHY
- health_status 不改变启用/禁用状态，仅用于健康展示和告警

### 删除约束

- 有 PUBLISHED 状态快照的数据源不可删除
- 有关联 skills.md 的数据源不可删除
- 软删除后 30 天可物理清理
