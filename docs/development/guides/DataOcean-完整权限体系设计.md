# DataOcean 完整权限体系设计

## 1. 设计目标

DataOcean 的权限系统不能只解决普通后台的“菜单权限”。

它需要同时解决：

```text
谁可以进入哪些系统功能
+
谁可以访问哪些数据
+
AI 可以看到哪些 Schema
+
生成的 SQL 最终可以访问哪些数据
```

最终权限体系采用：

```text
RBAC
+
Data Permission
+
Row Policy
+
Mask Policy
+
Permission-aware RAG
```

---

# 2. 核心模型

权限体系包含四个核心对象：

```text
User
Role
Department
Data Permission
```

职责如下。

| 对象 | 职责 |
|---|---|
| User | 谁在使用系统 |
| Role | 能做什么 |
| Department | 属于哪里，并提供默认数据范围 |
| Data Permission | 能访问什么数据 |

整体关系：

```text
                         User
              ┌───────────┼───────────┐
              ↓           ↓           ↓
            Role      Department   User Grant
              │           │           │
              ↓           └─────┬─────┘
     Function Permission        │
                                ↓
                        Data Permission
```

---

# 3. User 用户

User 是最终权限主体。

用户可以拥有：

```text
User
├── 多个 Role
├── 一个或多个 Department
└── User Data Grant
```

其中：

- Role 提供系统功能权限
- Department 提供默认数据权限
- User Data Grant 用于特殊授权和临时授权

原则上不要直接给用户配置普通功能权限。

---

# 4. Role 角色

Role 主要负责：

```text
Function Permission
```

例如角色：

```text
普通问数用户
数据分析师
数据治理人员
数据管理员
部门管理员
系统管理员
审计人员
```

功能权限示例：

```text
query:use
query:history:view

datasource:view
datasource:create
datasource:update
datasource:delete

asset:view

metadata:view
metadata:manage
metadata:publish

metric:view
metric:manage

permission:view
permission:manage

audit:view
audit:export

user:view
user:manage

role:view
role:manage

department:view
department:manage
```

用户通过：

```text
User
↓
UserRole
↓
Role
↓
RolePermission
↓
FunctionPermission
```

获得功能权限。

---

# 5. Role 也允许拥有 Data Permission

Role 的主要职责仍然是功能权限。

但是为了支持跨部门职责，Role 可以额外绑定 Data Permission。

例如：

```text
审计员
→ 可以读取多个部门的数据

集团数据分析师
→ 可以查询集团级汇总数据

数据治理管理员
→ 可以管理特定数据域
```

因此 Data Permission 的授权主体统一支持：

```text
USER
ROLE
DEPARTMENT
```

但普通业务数据默认优先通过 Department 分配。

---

# 6. Department 部门

Department 表示组织结构。

例如：

```text
公司
├── 销售中心
│   ├── 国内销售部
│   └── 海外销售部
├── 财务中心
├── 人力资源部
└── 数据中心
```

部门主要负责：

```text
组织归属
+
默认数据权限
```

例如：

```text
销售部
→ sales_order
→ sales_customer
→ sales_target
```

销售部成员默认获得这些资源的权限。

---

# 7. 部门权限继承

部门支持父子结构和权限继承。

例如：

```text
销售中心
├── 国内销售部
└── 海外销售部
```

销售中心的某项数据权限可以配置：

```text
CURRENT
```

只对当前部门有效。

或者：

```text
CURRENT_AND_CHILDREN
```

对子部门同时生效。

这样可以避免给每个子部门重复授权。

---

# 8. Data Resource 数据资源

所有可授权的数据统一抽象成：

```text
DataResource
```

资源类型：

```text
DATASOURCE
DATABASE
SCHEMA
TABLE
COLUMN
METRIC
```

形成资源树：

```text
DataSource
└── Database
    └── Schema
        └── Table
            └── Column
```

Metric 作为独立业务资源，与底层表和字段建立依赖关系。

不要为每种资源单独设计完全不同的权限系统。

---

# 9. Data Permission 数据权限

Data Permission 表示：

> 某个授权主体对某个 DataResource 拥有什么数据操作权限。

授权主体：

```text
USER
ROLE
DEPARTMENT
```

资源类型：

```text
DATASOURCE
DATABASE
SCHEMA
TABLE
COLUMN
METRIC
```

权限操作：

```text
READ
QUERY
EXPORT
MANAGE
```

含义：

| 权限 | 含义 |
|---|---|
| READ | 查看数据资产、元数据或数据 |
| QUERY | 允许参与智能问数 |
| EXPORT | 允许导出 |
| MANAGE | 允许治理或管理该数据资源 |

因此系统可以实现：

```text
可以 QUERY
但不能 EXPORT
```

---

# 10. ALLOW / DENY

所有数据权限必须支持：

```text
ALLOW
DENY
```

并采用：

```text
Default Deny
```

原则。

也就是说：

> 没有明确获得权限，就默认没有权限。

例如：

```text
销售部
ALLOW
sales_order
QUERY
```

同时：

```text
销售部
DENY
sales_order.cost_price
READ
```

表示销售部可以使用订单表，但不能访问成本字段。

---

# 11. 数据资源权限继承

DataResource 是树结构，因此权限支持向下继承。

例如：

```text
sales_db
ALLOW QUERY
inherit = true
```

则默认继承到：

```text
Schema
Table
Column
```

但子资源可以覆盖父级权限。

例如：

```text
sales_db
ALLOW QUERY

salary
DENY QUERY
```

最终：

```text
sales_db 中其他表允许
salary 禁止
```

---

# 12. 权限冲突优先级

最终权限由 Permission Resolver 统一计算。

建议优先级：

```text
Explicit DENY
>
User ALLOW
>
Role ALLOW
>
Department ALLOW
>
Inherited Resource ALLOW
>
Default DENY
```

核心原则：

```text
DENY 优先
```

任何明确 DENY 都不能被低优先级 ALLOW 覆盖。

---

# 13. Permission Resolver

系统增加统一权限计算服务：

```text
Permission Resolver
```

负责计算：

```text
Effective Permission
```

用户最终权限来源：

```text
User Direct Permission
+
Role Data Permission
+
Department Data Permission
+
Parent Department Permission
+
DataResource Inheritance
```

同时处理：

```text
ALLOW / DENY
有效时间
Column Permission
Row Policy
Mask Policy
```

最终得到：

```text
PermissionContext
```

---

# 14. PermissionContext

每次智能问数和数据访问都生成统一权限上下文。

例如：

```text
PermissionContext

userId

roleIds

departmentIds

authorizedDatasourceIds

authorizedSchemaIds

authorizedTableIds

columnPolicies

rowPolicies

maskPolicies

deniedResources
```

Spring Boot 是权限中心。

Agent 不自己重新发明另一套权限规则。

---

# 15. Column Permission 字段级权限

字段级权限需要支持：

```text
ALLOW
DENY
MASK
```

例如：

```text
customer.name
ALLOW

customer.phone
MASK

customer.id_card
DENY
```

最终效果：

```text
姓名：张三

手机号：138****8000

身份证：不可访问
```

---

# 16. 敏感等级

数据治理阶段需要给字段配置敏感等级。

建议：

```text
PUBLIC
INTERNAL
SENSITIVE
HIGHLY_SENSITIVE
```

典型敏感数据：

```text
手机号
身份证
银行卡
邮箱
地址
工资
```

敏感等级本身不直接等同于权限，但可以作为权限和脱敏策略的依据。

---

# 17. Mask Policy 脱敏策略

系统建立统一 Mask Policy。

支持：

```text
PHONE
EMAIL
ID_CARD
BANK_CARD
NAME
PARTIAL
FULL
CUSTOM
```

例如：

```text
13800138000
↓
138****8000
```

Mask Policy 可以绑定具体 Column。

用户有表访问权限时，也可以因为字段策略而只能看到脱敏后的结果。

---

# 18. Row Policy 行级权限

Row Policy 控制：

> 同一张表，不同用户最终可以看到哪些记录。

例如：

```text
销售一部
→ department_id = 101

销售二部
→ department_id = 102
```

用户生成：

```sql
SELECT SUM(amount)
FROM sales_order;
```

如果当前用户只能访问：

```text
department_id = 101
```

最终执行 SQL 应被安全改写为：

```sql
SELECT SUM(amount)
FROM sales_order
WHERE department_id = 101;
```

---

# 19. 动态行级策略

Row Policy 应支持有限的安全变量：

```text
$currentUser.id

$currentUser.departmentId

$currentUser.departmentIds
```

例如：

```text
owner_id = $currentUser.id
```

表示：

> 只能访问属于当前用户自己的记录。

动态表达式必须通过受控 DSL 或 AST 构建，不允许直接执行任意用户输入 SQL。

---

# 20. SQL 权限改写

DataOcean 已有 SQL 安全校验链路，因此权限系统需要接入 SQL AST。

最终：

```text
Generated SQL
↓
SQL Parser
↓
Permission Validator
↓
Row Policy Rewrite
↓
Column / Mask Policy
↓
SQL Executor
```

SQL 改写使用：

```text
sqlglot AST
```

不要使用字符串拼接的方式插入 WHERE。

---

# 21. Permission-aware Schema RAG

这是 DataOcean 权限系统与智能问数结合的核心。

原则：

> 用户无权访问的数据，不应该先暴露给 LLM，再等 SQL 执行阶段拒绝。

问数链路：

```text
Question
↓
Current User
↓
Permission Resolver
↓
Authorized Resources
↓
Schema Retriever
↓
SQL Generator
```

Schema RAG 的检索空间必须等于：

```text
当前用户有 QUERY 权限
+
已经 PUBLISHED
```

的数据资源。

---

# 22. Schema RAG 权限过滤

Schema 向量数据需要携带：

```text
datasource_id

database_id

schema_id

table_id

column_ids

resource_id

governance_status

sensitivity_level
```

检索：

```text
Question
+
Authorized Resource IDs
↓
Vector Search
```

无权限数据不能被召回。

---

# 23. SQL Validator 二次校验

即使 RAG 已经进行了权限过滤，生成 SQL 后仍然必须重新检查。

SQL Validator 需要通过 AST 提取：

```text
所有 Table
所有 Column
所有 JOIN
所有 Subquery
所有 CTE
```

然后统一校验权限。

例如：

```sql
SELECT *
FROM sales_order o
JOIN salary s ON ...
```

即使：

```text
sales_order ✅
```

但：

```text
salary ❌
```

整个 SQL 仍然必须拒绝。

---

# 24. 指标权限

Metric 也属于 DataResource。

例如：

```text
销售额
GMV
利润率
人工成本
净利润
```

权限可以做到：

```text
销售部
→ 销售额 ✅
→ GMV ✅
→ 净利润 ❌
```

即使底层表可访问，也允许限制敏感业务指标。

---

# 25. 数据治理状态

DataOcean 的原则是：

```text
先治理
↓
再发布
↓
再问数
```

数据资源建议具备：

```text
DISCOVERED
DRAFT
REVIEWING
PUBLISHED
DISABLED
```

只有：

```text
PUBLISHED
```

的数据资源才能进入正式问数范围。

---

# 26. 数据治理与权限的关系

数据治理阶段负责定义：

```text
业务名称

业务描述

字段描述

Join Path

指标定义

数据负责人

所属部门

敏感等级

脱敏策略

发布状态
```

权限中心负责定义：

```text
谁能 READ

谁能 QUERY

谁能 EXPORT

谁能 MANAGE

哪些行可以访问

哪些字段可以访问

哪些字段需要脱敏
```

不要把治理和权限混成一个概念。

---

# 27. 临时权限

Data Permission 支持：

```text
valid_from
valid_until
```

例如：

```text
张三
finance_report
QUERY

2026-09-10
~
2026-09-20
```

超过有效期后自动失效。

---

# 28. 权限申请与审批

用户没有某项权限时，可以发起：

```text
Permission Request
```

流程：

```text
用户申请
↓
数据负责人 / 部门负责人审批
↓
通过
↓
创建 Data Permission
↓
权限生效
↓
到期自动失效
```

申请状态：

```text
PENDING
APPROVED
REJECTED
CANCELLED
EXPIRED
```

---

# 29. Data Owner 数据负责人

DataResource 可以配置：

```text
owner_user_id
owner_department_id
```

主要用于：

```text
数据治理责任

权限审批

数据质量责任

异常处理
```

---

# 30. Audit 审计

所有关键权限行为和问数行为必须审计。

需要记录：

```text
谁

什么时候

使用了哪些角色和部门权限

问了什么

召回了哪些 Schema

生成了什么 SQL

最终执行了什么 SQL

访问了哪些 Table

访问了哪些 Column

应用了哪些 Row Policy

应用了哪些 Mask Policy

是否被拒绝

拒绝原因
```

---

# 31. Spring Boot 职责

Spring Boot 作为整个权限中心。

负责：

```text
认证

User

Role

Department

Function Permission

Data Permission

Permission Resolver

Permission Request

Permission Approval

Audit

Permission Cache
```

推荐统一提供：

```text
PermissionService
```

核心能力：

```text
getUserPermissionContext(userId)

getFunctionPermissions(userId)

getAuthorizedDataSources(userId)

getAuthorizedSchemas(userId)

getAuthorizedTables(userId)

getAuthorizedColumns(userId, tableId)

getRowPolicies(userId, tableId)

getMaskPolicies(userId, tableId)

hasPermission(userId, resourceId, permissionType)
```

---

# 32. Python Agent 职责

Python 不维护独立权限数据库。

Python Agent 负责：

```text
Schema RAG 权限过滤

SQL AST 解析

Table / Column 权限二次检查

Row Policy SQL Rewrite

Mask Policy 处理
```

权限规则的最终来源仍然是 Java 权限中心。

---

# 33. LangGraph 权限接入

现有问数流程需要加入权限节点。

最终推荐：

```text
START
↓
Permission Resolver
↓
Query Rewrite
↓
Schema Retriever
↓
SQL Generator
↓
SQL Validator
↓
Permission Rewriter
↓
SQL Executor
↓
Data Visualizer
↓
END
```

权限上下文在整个 Workflow 中传递。

---

# 34. 权限缓存

完整权限计算可以使用 Redis 缓存。

例如：

```text
permission:user:{userId}
```

缓存：

```text
Role

Department

Function Permission

Authorized Resources

Column Policy

Row Policy

Mask Policy
```

以下情况发生时立即失效：

```text
用户角色变化

用户部门变化

Role Permission 变化

Department Permission 变化

User Permission 变化

Row Policy 变化

Mask Policy 变化
```

---

# 35. 推荐核心表

## 基础权限

```text
sys_user

sys_role

sys_permission

sys_user_role

sys_role_permission

sys_department

sys_user_department
```

## 数据资源与权限

```text
data_resource

data_permission

row_policy

mask_policy
```

## 权限流程

```text
permission_request
```

## 审计

```text
audit_log
```

---

# 36. data_permission 核心字段建议

```text
id

subject_type
subject_id

resource_id

permission_type

effect

inherit

department_scope

valid_from
valid_until

grant_type
grant_reason

created_by
create_time
update_time
```

其中：

```text
subject_type
=
USER / ROLE / DEPARTMENT
```

```text
permission_type
=
READ / QUERY / EXPORT / MANAGE
```

```text
effect
=
ALLOW / DENY
```

---

# 37. 前端权限中心最终需要支持

```text
用户管理

角色管理

部门管理

功能权限

数据权限

行级策略

脱敏策略

权限申请

审批中心

审计日志
```

但最终是否拆成多少个一级 / 二级菜单，需要由前端信息架构统一规划。

---

# 38. 最终完整权限链路

```text
                         USER
                          │
             ┌────────────┼────────────┐
             ↓            ↓            ↓
           ROLE       DEPARTMENT   USER GRANT
             │            │            │
             ↓            └─────┬──────┘
     FUNCTION PERMISSION         │
                                 ↓
                         DATA PERMISSION
                                 │
                                 ↓
                          DATA RESOURCE
                                 │
        ┌───────────┬────────────┼────────────┐
        ↓           ↓            ↓            ↓
   DATASOURCE     TABLE        COLUMN       METRIC
                     │            │
                     ↓            ↓
                ROW POLICY    MASK POLICY
                     │            │
                     └─────┬──────┘
                           ↓
                  PERMISSION RESOLVER
                           ↓
                 EFFECTIVE PERMISSION
                           ↓
          ┌────────────────┼────────────────┐
          ↓                ↓                ↓
     SCHEMA RAG       SQL VALIDATOR    SQL REWRITER
          │                │                │
          └────────────────┼────────────────┘
                           ↓
                     SQL EXECUTOR
                           ↓
                         AUDIT
```

---

# 39. 最终职责

```text
User
=
谁

Role
=
能做什么

Department
=
属于哪里 + 默认数据范围

Function Permission
=
能使用哪些系统功能

Data Permission
=
能访问哪些数据资源

Column Permission
=
能访问哪些字段

Row Policy
=
能访问哪些记录

Mask Policy
=
敏感字段最终看到什么

Permission Resolver
=
计算用户最终有效权限

Permission-aware RAG
=
决定 AI 能看到哪些 Schema

SQL Validator
=
决定生成 SQL 是否有权执行

Permission Rewriter
=
根据行级、字段级策略安全改写 SQL

Audit
=
记录谁在什么时候访问了什么
```

---

# 40. 最终原则

整个 DataOcean 权限系统必须遵守以下原则：

1. 默认拒绝，明确授权。
2. DENY 优先。
3. Role 主要负责功能权限。
4. Department 负责默认数据范围。
5. Role 可以为跨部门职责获得数据权限。
6. User 可以获得特殊和临时数据权限。
7. Department 支持父子部门权限继承。
8. DataResource 支持父子资源权限继承。
9. 未发布的数据不能进入正式问数。
10. 无 QUERY 权限的数据不能进入 Schema RAG。
11. RAG 权限过滤不能代替 SQL 执行前权限校验。
12. SQL 权限改写必须基于 AST，不使用字符串拼接。
13. 前端权限只负责体验，真正安全边界必须在后端。
14. 所有问数和敏感数据访问必须可审计。
