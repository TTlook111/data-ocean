# Data Model: 权限与安全模块

## Entity Relationship

```
sys_user/sys_role/sys_department ──< datasource_access (N)
datasource_access (1) ──< datasource_access_policy (N)
datasource (1) ──< datasource_access (N)
```

## Tables

### datasource_access

数据源级访问授权，控制谁能访问哪个数据源。

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 授权ID |
| datasource_id | BIGINT | FK → datasource.id, NOT NULL | 数据源ID |
| subject_type | VARCHAR(20) | NOT NULL | 授权主体类型: USER/ROLE/DEPARTMENT |
| subject_id | BIGINT | NOT NULL | 授权主体ID (用户ID/角色ID/部门ID) |
| can_query | TINYINT | NOT NULL, DEFAULT 1 | 是否允许查询 |
| can_export | TINYINT | NOT NULL, DEFAULT 0 | 是否允许导出 |
| can_view_sql | TINYINT | NOT NULL, DEFAULT 1 | 是否允许查看生成的 SQL |
| created_by | BIGINT | FK → sys_user.id | 创建人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |

UNIQUE INDEX: (datasource_id, subject_type, subject_id)

### datasource_access_policy

行列级访问策略，细粒度控制表/列/行的访问权限。

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 策略ID |
| datasource_id | BIGINT | FK → datasource.id, NOT NULL | 数据源ID |
| subject_type | VARCHAR(20) | NOT NULL | 授权主体类型: USER/ROLE/DEPARTMENT |
| subject_id | BIGINT | NOT NULL | 授权主体ID |
| table_name | VARCHAR(100) | NOT NULL | 表名（* 表示所有表） |
| column_name | VARCHAR(100) | | 列名（NULL 表示表级策略） |
| access_type | VARCHAR(20) | NOT NULL, DEFAULT 'ALLOW' | ALLOW/DENY/MASK |
| mask_strategy | VARCHAR(20) | | 脱敏策略: PHONE/ID_CARD/EMAIL/BANK_CARD/NAME |
| row_filter_expression | VARCHAR(500) | | 行级过滤 SQL 表达式 (如 region='华东') |
| created_by | BIGINT | FK → sys_user.id | 创建人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |

INDEX: (datasource_id, subject_type, subject_id, table_name)

## Permission Context (传给 Python 的 JSON)

Java 每次查询请求时计算并传给 Python：

```json
{
  "allowedTables": ["orders", "customers", "products"],
  "deniedColumns": {
    "customers": ["id_card", "bank_account"]
  },
  "maskColumns": {
    "customers": {
      "phone": "PHONE",
      "email": "EMAIL"
    }
  },
  "rowFilters": {
    "orders": "region = '华东'"
  }
}
```

## Role Permission Matrix

| 功能 | USER | ANALYST | DATA_MANAGER | SECURITY_MANAGER | ADMIN |
|------|------|---------|--------------|------------------|-------|
| 自然语言查询 | Y | Y | Y | Y | Y |
| 查看生成 SQL | N | Y | Y | Y | Y |
| 导出结果 | N | Y | N | Y | Y |
| 元数据治理 | N | N | Y | N | Y |
| skills.md 编辑 | N | Y | Y | N | Y |
| 权限配置 | N | N | N | Y | Y |
| 用户管理 | N | N | N | N | Y |
| Prompt 管理 | N | N | N | N | Y |

## Masking Rules

| Strategy | Pattern | Example |
|----------|---------|---------|
| PHONE | 保留前3后4 | 138****5678 |
| ID_CARD | 保留前4后4 | 3101**********1234 |
| EMAIL | 保留前3 + 域名 | zha***@example.com |
| BANK_CARD | 仅保留后4 | ****5678 |
| NAME | 保留姓 | 张* |

## Validation Rules

- subject_type: 必须为 USER/ROLE/DEPARTMENT 之一
- subject_id: 必须引用已存在的用户/角色/部门
- datasource_id: 必须引用已存在的数据源
- table_name: 必须为数据源中实际存在的表名（或 *）
- column_name: 必须为对应表中实际存在的列名
- row_filter_expression: 必须为合法 SQL WHERE 子句片段，通过 sqlglot 解析验证
- mask_strategy: access_type 为 MASK 时必填
