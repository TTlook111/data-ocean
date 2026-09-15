# DataOcean 完整权限体系设计与开发指导

> 文档状态：轨道 B 实施基线，尚未代表功能已完成
>
> 现状核查基线：2026-09-15，基线提交 `main` / `45efbe7`，文档分支 `codex/permission-system-guide`
>
> 适用范围：功能权限、数据源/表/列/行权限、脱敏、访问审批、Permission-aware RAG、SQL 执行前校验、前端权限消费与审计

## 0. 使用规则

### 0.1 这份文档能否直接指导开发

原始版本可以作为目标模型输入，但不能直接当作实施清单。原文对 User、Role、Department、Data Permission、Row Policy、Mask Policy 和 Permission-aware RAG 的方向判断基本正确，但缺少以下开发必需信息：

- 当前实现与目标模型的差异；
- 唯一且可测试的权限冲突算法；
- 现有表的复用、迁移和兼容策略；
- Java、Python、前端之间的请求契约；
- 7 个后台业务域、19 个二级工作区和工作台的权限消费规则；
- 分阶段上线、回滚、测试和真实验收标准。

本文已补齐这些内容。开发时必须区分以下标记：

- **[现状]**：当前代码已经存在的行为；
- **[目标]**：轨道 B 最终必须达到的行为；
- **[实施]**：从现状迁移到目标的具体要求。

不得把 [目标] 或 [实施] 写成当前已经完成的事实。

### 0.2 信息来源与冲突处理

实施前按以下顺序核对：

1. 当前数据库迁移、Java/Python/前端源代码和测试；
2. 本文的目标语义与实施顺序；
3. `DataOcean后台重构状态与整改计划.md` 的当前阶段结论；
4. `后续开发.md` 的执行队列。

若本文与当前代码不同，先判断它属于“目标差异”还是文档失准，不得静默选择其中一个版本。涉及默认允许/拒绝、DENY 覆盖、超级管理员数据权限、行过滤合并、审批越权或迁移回填的差异，必须先补测试再改实现。

### 0.3 不可突破的边界

1. 前端只负责可见性和交互体验，不构成安全边界。
2. Java 是权限事实、生命周期、审批、缓存失效和审计的唯一管理方。
3. Python 不持久化权限；只消费 Java 提供的请求级权限快照。
4. 表/列校验和行过滤必须基于 `sqlglot` AST，不使用字符串拼接。
5. Python 识别需要脱敏的结果列，Java 对结果执行最终脱敏；不得只依赖模型或前端脱敏。
6. `BLOCKED`、`DEPRECATED` 和未发布对象不能被普通授权、临时审批或超级管理员问数绕过。
7. 新增缓存只能使用 Redis；缓存失败必须降级到数据库实时计算，不能放宽权限。
8. 数据库迁移不创建外键，关系完整性由服务层校验。
9. 改造期间不得先删除旧权限数据；必须先双读校验、再切换、最后清理。
10. 每个阶段分别报告代码完成、自动化测试、运行健康、真实接口和浏览器验收。

### 0.4 推荐阅读路径

- 产品与安全决策：先读第 12、15、17、19、28、37、48 节；
- 开发实施：先读第 41～50 节，再回查第 1～40 节的领域定义；
- Java：重点读第 13、14、27～31、34～36、43～47 节；
- Python：重点读第 19～23、32～33、42～44、47 节；
- 前端：重点读第 37、42、44、46～48 节。

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

## 2. 核心模型

权限体系包含七类核心对象：

```text
User
Role
Department
Function Permission
Data Resource
Data Permission
Permission Context
```

职责如下。

| 对象 | 职责 |
|---|---|
| User | 谁在使用系统 |
| Role | 能做什么 |
| Department | 属于哪里，并提供默认数据范围 |
| Function Permission | 能进入哪个工作区、查看哪个 Tab、执行哪个操作 |
| Data Resource | 被授权的数据源、表、列或指标 |
| Data Permission | 能访问什么数据 |
| Permission Context | 某个用户在某次请求中的最终有效权限快照 |

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

## 3. User 用户

User 是最终权限主体。

用户可以拥有：

```text
User
├── 多个 Role
├── 一个主 Department
└── User Data Grant
```

其中：

- Role 提供系统功能权限
- Department 提供默认数据权限
- User Data Grant 用于特殊授权和临时授权

原则上不要直接给用户配置普通功能权限。

**[现状]** `sys_user.department_id` 只支持一个主部门，角色支持多个；当前没有 `sys_user_department`。

**[目标]** 轨道 B 保持“一个主部门 + 多角色”，不在本阶段顺带引入多部门成员关系。将来确有矩阵组织需求时，再单独设计 `sys_user_department`、主部门标记和继承冲突规则。

---

## 4. Role 角色

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

## 5. Role 也允许拥有 Data Permission

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

## 6. Department 部门

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

## 7. 部门权限继承

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

**[现状]** 当前数据源权限计算会沿 `sys_department.parent_id` 向上收集整条部门路径，相当于父部门授权自动对子部门生效，但现有 `datasource_access` 没有保存 `CURRENT` / `CURRENT_AND_CHILDREN`，因此管理员无法选择继承范围。

**[目标]** 部门授权必须显式保存 `department_scope`：

```text
CURRENT
CURRENT_AND_CHILDREN
```

`CURRENT` 只匹配授权部门本身；`CURRENT_AND_CHILDREN` 才允许沿组织树向下生效。部门被禁用、移动或形成异常循环时，解析器必须拒绝继承并记录告警，不能静默扩大范围。

---

## 8. Data Resource 数据资源

所有可授权的数据统一抽象成：

```text
DataResource
```

逻辑资源类型：

```text
DATASOURCE
TABLE
COLUMN
METRIC
```

形成资源树：

```text
DataSource
└── Table
    └── Column
```

Metric 作为独立业务资源，与底层表和字段建立依赖关系。

不要为每种资源单独设计完全不同的权限系统。

**[范围决定]** 当前 MVP 是“每次选择一个 MySQL 数据源，在该库内进行多表查询”。轨道 B 不新增 DATABASE / SCHEMA 两级授权，也不为了概念完整度创建一套与现有元数据重复的资源树。未来支持一个连接下多库、多 Schema 时再扩展资源层级。

**[实施]** 现有 `datasource`、当前发布快照中的表/列元数据和稳定的 `datasource_id + table_name + column_name` 继续作为资源定位依据。`metadata_entity` 发布时会重建数据源实体，不能在未保证 ID 稳定前把权限永久绑定到其自增 ID。指标权限只有在指标实体、依赖关系和查询链路均正式落地后才启用，不能只加一个枚举值。

---

## 9. Data Permission 数据权限

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
TABLE
COLUMN
METRIC
```

权限操作：

```text
DISCOVER
QUERY
EXPORT
VIEW_SQL
MANAGE
```

含义：

| 权限 | 含义 |
|---|---|
| DISCOVER | 查看资产名称、结构和治理信息，不等于读取业务数据 |
| QUERY | 允许参与智能问数 |
| EXPORT | 允许导出 |
| VIEW_SQL | 允许查看生成和最终执行的 SQL |
| MANAGE | 允许治理或管理该数据资源 |

因此系统可以实现：

```text
可以 QUERY
但不能 EXPORT 或 VIEW_SQL
```

功能权限与数据权限必须分开：`metadata:view` 只代表能进入资产页面，`DISCOVER` 决定能看到哪些资产；`query:use` 只代表能使用问数功能，数据源的 `QUERY` 决定能问哪些数据。

---

## 10. ALLOW / DENY

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
DISCOVER / QUERY
```

表示销售部可以使用订单表，但不能访问成本字段。

---

## 11. 数据资源权限继承

DataResource 是树结构，因此权限支持向下继承。

例如：

```text
sales_db
ALLOW QUERY
inherit = true
```

则默认继承到：

```text
Table
Column
```

子资源可以在父级 ALLOW 基础上继续收窄权限；父级 DENY 不能被子资源 ALLOW 放开。

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

通配授权必须明确是否覆盖未来资源：

- `include_future_resources = false`：授权时固化当前发布快照中的资源集合，新发布的表/列默认不自动获得权限；这是默认值。
- `include_future_resources = true`：未来新增资源也继承授权，只允许管理员在确认风险后显式选择，并记录审计。

`false` 模式通过 `granted_snapshot_id` 读取授权时资源集合，再与当前活动快照取交集；不要求为每张表复制一条策略。授权时快照不存在或历史快照已不可读时必须拒绝创建/解析，不能退化为当前全量资源。

活动快照发布后，Java 必须对权限资源做一致性检查：已删除/重命名对象标为失效或待处理，新对象按上述开关决定是否继承。不能因为策略只保存字符串名称，就把一个已删除后同名重建但语义不同的对象静默视为原资源。

表列匹配使用当前发布快照返回的规范名称和数据源方言规则。不得一律 `toLowerCase()` 后假设所有 MySQL 环境大小写语义相同；需要保存规范名并使用单独的比较键。

---

## 12. 权限冲突优先级

最终权限由 Permission Resolver 统一计算。

最终决策顺序固定为：

```text
治理硬阻断（未发布 / BLOCKED / DEPRECATED）
>
同一 action 上有效的显式 DENY（任一 USER / ROLE / DEPARTMENT 来源）
>
同一 action 上有效的显式 ALLOW（USER / ROLE / DEPARTMENT 取并集）
>
Default DENY
```

核心原则：

```text
DENY 优先
```

任何明确 DENY 都不能被低优先级 ALLOW 覆盖。

补充规则：

1. USER、ROLE、DEPARTMENT 是授权来源，不是绕过 DENY 的覆盖层；用户 ALLOW 不能覆盖角色或部门 DENY。
2. 父资源 ALLOW 可以向下继承，子资源 DENY 可以收窄；父资源 DENY 不能被子资源 ALLOW 放开。
3. `priority` 只用于同类、同资源、同 effect 策略的确定性排序，不能让 ALLOW 越过 DENY。
4. 过期、未生效或时间计划不匹配的策略不参与计算。
5. 时间计划解析失败时，DENY 继续生效，ALLOW 视为无效。
6. 无任何有效授权时返回明确的 `DENY / NO_GRANT`，不得用空列表表达“无限制”。

**[现状差异]** 当前数据源级计算采用“部门 → 角色 → 用户覆盖”，用户授权可能覆盖之前的拒绝；细粒度策略在无 ALLOW 时可能产生 `UNRESTRICTED`。两者均不符合上述目标，必须在切换默认拒绝前完成数据回填和回归测试。

---

## 13. Permission Resolver

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

Permission Resolver 的输出必须包含“结果”和“解释”，至少能回答：

```text
decision: ALLOW / DENY
reasonCode
matchedGrantIds
matchedPolicyIds
decisionSource
expiresAt
```

解释信息用于管理员权限预览和审计，但不得把其他主体的敏感策略表达式返回给普通用户。

---

## 14. PermissionContext

每次智能问数和数据访问都生成统一权限上下文。

例如：

```text
PermissionContext

userId

datasourceId

snapshotId

roleIds

departmentIds

allowedTables

columnScopeModes

allowedColumns

columnPolicies

rowPolicies

maskPolicies

deniedResources

canQuery

canExport

canViewSql

decisionVersion

permissionExpiresAt
```

Spring Boot 是权限中心。

Agent 不自己重新发明另一套权限规则。

**[实施契约]** Java 生成请求级 `PermissionContext` 后随 `/internal/query/execute` 发送给 Python。Python 模型字段保持 camelCase/snake_case 双向兼容，但新增字段必须同时更新 Java VO、Java client、Python Pydantic 模型和契约测试。`tableScopeMode` 只允许：

```text
ALLOWLIST    # allowedTables 为空表示拒绝全部表
UNRESTRICTED # 只允许受控的超级管理员兼容期使用，轨道 B 完成后不作为普通用户结果
```

---

## 15. Column Permission 字段级权限

字段级权限需要支持：

```text
ALLOW
DENY
MASK
UNMASK（仅审批产生的限时例外）
```

表获得 QUERY 后，列范围有两种明确模式：

```text
ALL_EXCEPT_DENIED  # 迁移兼容默认值：除 DENY 外均可用
ALLOWLIST          # 只允许显式列 ALLOW；空列表表示不能查询该表
```

列 ALLOW 只在 `ALLOWLIST` 模式下有意义，不能单独授予表查询权；必须先通过数据源和表级 QUERY。DENY 在两种模式下都优先，MASK / UNMASK 只作用于最终仍允许访问的列。

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

**[现状差异]** 当前 `PermissionCalculatorImpl` 只处理列 DENY 和 MASK，带 `columnName` 的 ALLOW 不会进入最终上下文，也没有 `allowedColumns` 契约。轨道 B 若保留“列 ALLOW”能力，就必须按上述模式完整实现；不能让前端保存成功、执行端实际忽略。

---

## 16. 敏感等级

数据治理阶段需要给字段配置敏感等级。目标等级为：

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

**[现状]** 当前项目已经使用 `classification + tag` 治理体系，并通过 `PII.*` 标签生成 MASK 策略候选；没有独立的 `sensitivity_level` 权威字段。

**[实施]** 轨道 B 优先复用现有标签体系：如确需四级排序，在 classification 中建立“敏感等级”单选分类并把四个等级作为 Tag，不再给表、列和 `metadata_entity` 各加一套重复字段。PII 类型标签回答“是什么敏感信息”，敏感等级回答“风险有多高”，二者可以并存，但只有经过审核的标签才能自动产生权限/脱敏策略。

---

## 17. Mask Policy 脱敏策略

系统建立统一 Mask Policy。

**[现状]** Java `MaskStrategy` 当前支持：

```text
PHONE
EMAIL
ID_CARD
BANK_CARD
NAME
```

**[目标]** 轨道 B 增加 `FULL` 作为未知策略、冲突和高敏字段的安全兜底。`PARTIAL` 只有在参数结构、保留位数和类型校验明确后才能加入；首版不支持可执行脚本或任意表达式形式的 `CUSTOM`，避免把脱敏器变成代码执行入口。

例如：

```text
13800138000
↓
138****8000
```

Mask Policy 可以绑定具体 Column。

用户有表访问权限时，也可以因为字段策略而只能看到脱敏后的结果。

普通授权冲突顺序固定为：

```text
DENY > MANDATORY_MASK > 经审批的限时 UNMASK > OVERRIDABLE_MASK > 原值 ALLOW
```

`UNMASK` 只能是精确到 USER + COLUMN、带有效期并关联审批记录的例外，不能表级配置，也不能覆盖 DENY、治理硬阻断或 `MANDATORY_MASK`。现有 MASK 在迁移时默认按 `MANDATORY_MASK` 处理；只有人工确认允许申请原值的字段，才能标为 `OVERRIDABLE_MASK`。

同一列命中多个 MASK 策略时，按显式 `priority` 选择；相同优先级但策略不同属于配置冲突，必须拒绝发布或降级为 `FULL`，不能依赖数据库返回顺序。

**[职责边界]** Python 根据 SQL AST 识别输出列与物理敏感列的映射，并返回 `maskedFields`；Java 使用服务端策略对最终结果做脱敏。前端只展示“已脱敏”提示，不能接触原值后再遮盖。

---

## 18. Row Policy 行级权限

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

## 19. 动态行级策略

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

行级授权的合并不能简单把所有来源直接 `AND`：

- 多个“可访问范围”是授权并集，使用 `OR`；例如用户同时通过两个角色获得华东和华南范围，应得到 `region = 'east' OR region = 'south'`。
- 系统级强制约束是限制条件，使用 `AND`；例如租户隔离必须与上面的业务范围同时成立。
- 某个有效的表级 ALLOW 没有行条件时，表示该授权来源允许全表行范围，但仍受治理硬阻断、DENY 和系统级强制约束限制。
- 不在轨道 B 首版支持任意“行级 DENY 表达式”；如需支持，必须定义集合差语义并增加 AST 测试，不能把它混入字符串条件。

建议将行策略明确区分为：

```text
SCOPE       # 同类授权范围之间 OR
MANDATORY   # 强制约束之间 AND，并与 SCOPE 结果 AND
```

**[现状差异]** 当前 `row_filter_expression` 是 SQL 片段，Java 将多来源条件直接 AND 合并。轨道 B 需要先引入受控 DSL/结构化条件和上述分组语义，再逐步迁移旧表达式；旧表达式在未通过 AST 解析和列校验前不得进入新解析器。

首版 DSL 只支持白名单操作符和标量值/受控变量，例如：

```json
{
  "op": "AND",
  "conditions": [
    { "column": "department_id", "operator": "EQ", "valueRef": "$currentUser.departmentId" },
    { "column": "status", "operator": "IN", "values": ["PAID", "SHIPPED"] }
  ]
}
```

允许的叶子操作符建议仅包含 `EQ`、`NE`、`GT`、`GE`、`LT`、`LE`、`IN`、`NOT_IN`、`IS_NULL`、`IS_NOT_NULL`；逻辑节点只允许 `AND`、`OR`。列名必须属于目标表当前发布快照，值必须参数化绑定。首版不支持函数、子查询、任意 SQL、跨表列引用和用户自定义变量。

---

## 20. SQL 权限改写

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

AST 改写必须保持原查询语义：右表的行条件不能无条件塞进 `WHERE`，否则会把 `LEFT JOIN` 变成 `INNER JOIN`；应根据 JOIN 类型注入 `ON`、包装受限子查询或采用经过测试的等价结构。CTE、相关子查询、表别名、同表多次 JOIN 和聚合查询必须分别覆盖，无法证明安全时拒绝执行。

---

## 21. Permission-aware Schema RAG

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

**[现状差异]** 当前 `/internal/rag/retrieve` 请求没有携带允许表/禁止列，RAG 检索主要按数据源、活动快照和知识版本过滤；硬权限目前集中在 SQL 生成后的 AST 校验。这能阻止越权执行，但不能满足“无权 Schema 不暴露给 LLM”的目标。

**[实施]** 权限过滤必须同时覆盖：

1. Milvus 首次检索；
2. 相邻 chunk 扩展；
3. MySQL fallback chunks；
4. Schema Linking 输入；
5. SQL 自校正重试使用的上下文。

任何分支都不得在过滤前把 chunk 文本送入 LLM。若允许表为空，检索直接返回权限拒绝，不降级成全量检索。

### 21.1 LLM Context Firewall

Permission-aware 不能只过滤 Milvus。凡是会进入外部模型 Prompt 的上下文，都必须经过同一份请求级权限快照检查：

```text
conversation_history
conversation_summary
glossary_terms
RAG / fallback chunks
few-shot question + SQL
Schema Linking 输入
SQL 自校正错误上下文
图表生成 data_preview
推荐追问涉及的表名
```

具体规则：

1. 历史会话和长期摘要绑定 `datasourceId` 与生成时权限版本；再次问数前按当前权限裁剪。无法安全裁剪时丢弃历史增强上下文，不影响当前问题执行。
2. 权限撤销、数据源切换、活动快照切换后，不把旧摘要中的 SQL、表名和字段名继续发送给 LLM；需要时异步重建安全摘要。
3. glossary term 关联了表/列时按资源权限过滤；未绑定资源的纯业务术语可以保留，但不得夹带无权 Schema。
4. few-shot 示例必须通过 SQL AST 重新提取全部表列，确认均在当前权限内后才能进入 Prompt；只因“与一个允许表有交集”不能放行包含其他表的示例。
   自动成功的查询先作为用户级候选，不能直接变成同数据源所有用户共享的示例。提升为共享 few-shot 前需审核、参数化敏感字面量和行策略值，并保存所用表列与适用权限范围；撤权或快照变更后重新校验。
5. DENY 列的名称、描述、样例值和 SQL 均不进入 LLM。MASK 列可以进入 Schema 生成 SQL，但其原始结果值不能进入图表或解释模型。
6. 当前图表生成会把最多 20 行数据样本发送给 LLM。轨道 B 必须先按 `maskedFields` 生成安全预览，或改为本地确定性选图；不得把待脱敏原值先发给模型、最后才对用户脱敏。
7. 数据库错误进入 SQL 自校正前需清洗，不返回连接信息、其他库表名或敏感样例值。
8. Context Firewall 失败时删除对应增强上下文或停止查询，不能降级为未过滤上下文。

---

## 22. Schema RAG 权限过滤

Schema 向量数据需要携带：

```text
datasource_id
snapshot_id
doc_id
knowledge_version_no
table_name
related_tables
related_columns
entity_ids             # 辅助追踪，不作为唯一权限键
governance_status
review_status
approved_tag_fqns
```

当前 Milvus 已保存 `datasource_id`、快照/文档版本、`related_tables`、`related_columns`、`entity_ids` 和治理信息。轨道 B 优先基于稳定的表名/列名集合做请求级过滤；在 `metadata_entity` ID 生命周期稳定前，不把 `entity_ids` 作为唯一权限键。

检索：

```text
Question
+
Allowed Tables / Denied Columns
↓
Vector Search
```

无权限数据不能被召回。

---

## 23. SQL Validator 二次校验

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

## 24. 指标权限

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

**[范围说明]** 当前仓库没有已投入问数链路的独立指标资源授权模型。轨道 B 先预留 `METRIC` 语义，不创建无法被 Resolver、RAG 和 SQL 校验真实消费的空表或前端入口。指标实体和依赖图正式落地时，再把指标权限作为独立阶段接入。

---

## 25. 数据治理状态

DataOcean 的原则是：

```text
先治理
↓
再发布
↓
再问数
```

知识文档与元数据对象各自保留已有生命周期，不新增一套笼统的统一状态。进入问数链路至少需要同时满足：

```text
数据源已启用且连接健康
当前元数据快照已发布
表/列不是 BLOCKED 或 DEPRECATED
对应 skills.md 已审核、索引并发布
当前用户拥有有效 QUERY 权限
```

治理状态是权限解析前的硬约束，不是一条可以被普通 ALLOW 或临时审批覆盖的权限策略。

---

## 26. 数据治理与权限的关系

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
谁能 DISCOVER

谁能 QUERY

谁能 EXPORT

谁能 MANAGE

哪些行可以访问

哪些字段可以访问

哪些字段需要脱敏
```

不要把治理和权限混成一个概念。

---

## 27. 临时权限

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

时间语义必须统一：绝对时间按 UTC 写入和比较、前端按用户/系统时区展示；周期计划必须显式保存 `timezone`（本项目默认 `Asia/Shanghai`，不能依赖服务器本地时区）。Java 解析器注入可测试的 `Clock`，避免单元测试和夏令时/跨午夜行为依赖运行机器。

---

## 28. 权限申请与审批

用户没有某项查询权限，或命中可申请解除的脱敏策略时，可以发起：

```text
Permission Request
```

流程：

```text
用户申请
↓
权限管理员审批（轨道 B 首版）
↓
通过
↓
按申请类型创建有有效期的 ALLOW 或 UNMASK 策略
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

**[现状]** 当前申请入口说明是“因 MASK 结果申请查看原始数据”，但审批通过只创建临时 USER ALLOW；而 PermissionCalculator 对 MASK 取并集，ALLOW 无法移除 MASK，因此当前流程不能真正获得原值。申请表已有 PENDING / APPROVED / REJECTED / EXPIRED，但尚无 CANCELLED 端点，也没有保存“申请记录 → 生成策略”的精确关联。

**[目标]** 申请明确区分 `QUERY_ACCESS` 和 `UNMASK`。前者生成临时 ALLOW，后者只对 `OVERRIDABLE_MASK` 精确列生成临时 UNMASK。增加申请人取消 PENDING 申请、审批幂等/乐观锁和 `generated_policy_id` 等价关联。到期清理必须按关联策略删除，不能按“用户 + 数据源 + 表 + 到期时间”的宽条件删除。

---

## 29. Data Owner 数据负责人

目标 DataResource 可以配置：

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

**[现状]** 当前代码没有 `owner_user_id` / `owner_department_id` 的权威字段，访问申请由具备 `security:manage`（目标为 `security:approve`）的管理员审批。

**[范围决定]** 轨道 B 首版继续使用权限管理员审批，不把尚未存在的数据负责人伪装成审批路由。若本阶段增加 Data Owner，必须同时完成字段归属、历史回填、离职/部门变更转移、无负责人兜底和审批越权测试后才能切换。

---

## 30. Audit 审计

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

审计数据同样需要最小化：普通操作日志不重复保存完整结果集、密钥、连接凭据和原始令牌；问题文本、SQL、行策略等可能包含敏感值时，应定义保留周期、查看权限和必要的脱敏/加密方式。审计查询权限不自动等于业务数据原值权限。

---

## 31. Spring Boot 职责

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

统一由现有 permission / datasource / user 模块演进提供，不再平行创建第二套权限服务。对外建议收口为：

```text
PermissionService
```

核心能力：

```text
getUserPermissionContext(userId, datasourceId, activeSnapshotId)

getFunctionPermissions(userId)

getAuthorizedDataSources(userId)

getAuthorizedSchemas(userId)

getAuthorizedTables(userId)

getAuthorizedColumns(userId, tableId)

getRowPolicies(userId, tableId)

getMaskPolicies(userId, tableId)

hasPermission(userId, resourceId, permissionType)

explainDecision(userId, datasourceId, resource)
```

其中 `getUserPermissionContext` 必须带 `datasourceId + activeSnapshotId`，不能计算一个脱离数据源和发布快照的全局表权限。

**[现状复用]**：

- `sys_permission`、`sys_role_permission`、`sys_user_role`：继续承担功能权限；
- `datasource_access`：继续承担数据源级 QUERY / EXPORT / VIEW_SQL 和 ALLOW / DENY；
- `datasource_access_policy`：继续承担表、列、行过滤和 MASK；
- `access_approval_request`：继续承担临时访问申请；
- `permission_change_log`、查询审计和操作日志：继续承担审计；
- `PermissionCalculator`：演进为统一解析入口；
- `/api/admin/datasource-access/decision`：演进为正式权限解释端点。

不得在没有迁移收益证明的情况下，立即复制出 `data_resource`、`data_permission`、`row_policy`、`mask_policy` 四套新表并让新旧模型长期双写。

---

## 32. Python Agent 职责

Python 不维护独立权限数据库。

Python Agent 负责：

```text
Schema RAG 权限过滤

SQL AST 解析

Table / Column 权限二次检查

Row Policy SQL Rewrite

识别需要脱敏的输出字段并回传脱敏提示
```

权限规则的最终来源仍然是 Java 权限中心。

Python 不负责判断用户、角色、部门或审批状态，也不查询 Java 权限表。最终结果值的脱敏由 Java 完成。

---

## 33. LangGraph 权限接入

权限解析发生在 Java 发起 Python 请求之前。LangGraph 不新增一个会自行查询权限数据库的 `Permission Resolver` 节点；图内各节点只消费同一份不可变的请求级权限快照。

最终推荐：

```text
START
↓
Query Rewrite
↓
Schema Retriever（按权限过滤）
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

Java 侧调用顺序：

```text
认证用户
→ 校验 query:use
→ 校验数据源 readiness
→ Permission Resolver 计算并冻结 PermissionContext
→ 调用 Python /internal/query/execute
```

---

## 34. 权限缓存

完整权限计算使用现有 `RedisTemplate<String, Object>` 缓存。

例如：

```text
permission:context:{userId}:{datasourceId}:{snapshotId}
```

`decisionVersion` 保存在缓存值和审计中，建议由参与决策的用户/角色/部门关系、有效授权/策略 ID 与更新时间、活动快照 ID 经过稳定排序后计算摘要。不能把一个尚未计算出来的 `decisionVersion` 放进读取缓存所需的 key，否则每次命中缓存前仍要完整计算权限。

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

还必须在以下事件后失效：数据源授权变化、部门父子关系变化、治理状态变化、活动快照切换、审批通过/到期、角色停用、用户停用。

缓存规则：

1. 先提交数据库事务，再失效缓存；
2. ROLE / DEPARTMENT 变化必须失效所有受影响用户，不能把 `subjectId` 当成 `userId` 精确删除；
3. Redis 不可用时回源数据库，禁止返回“无限制”上下文；
4. 可通过短 TTL + `decisionVersion` 降低漏删风险，但 TTL 不能代替事件失效；
5. 缓存中不存原始行级敏感值、密钥或连接凭据。

为避免 Redis `KEYS` 扫描，写入上下文缓存时同步维护 `permission:index:user:{userId}` 和 `permission:index:datasource:{datasourceId}` 等索引集合；角色或部门变化先查询受影响用户，再按索引删除。索引维护失败时依靠短 TTL 收敛，但当前请求仍回源数据库，不能用旧缓存放行。

**[现状差异]** `PermissionCalculatorImpl` 当前直接使用 Caffeine 本地缓存，与项目“新增缓存 Redis-only”的约束不一致，而且角色/部门事件无法仅凭主体 ID 安全定位所有用户。轨道 B 必须迁移并补多实例一致性测试。

---

## 35. 现有表与目标职责

### 基础权限（保留）

```text
sys_user

sys_role

sys_permission

sys_user_role

sys_role_permission

sys_department

```

轨道 B 不创建 `sys_user_department`；当前继续使用 `sys_user.department_id`。

### 数据资源与权限（演进）

```text
datasource

datasource_access

datasource_access_policy

metadata_entity（只作资产/关系图，不直接作为不稳定的权限主键）
```

### 权限流程

```text
access_approval_request
```

### 审计

```text
permission_change_log

query_audit_log

sys_operation_log
```

只有当现有两层表无法表达已确认需求时，才允许通过新迁移拆表；拆表必须提供回填 SQL、双读对账、切换开关和回滚方案。

---

## 36. 现有权限表的目标字段

### 36.1 `datasource_access`

```text
id
datasource_id
subject_type          # USER / ROLE / DEPARTMENT
subject_id
access_effect         # 旧的整条授权 ALLOW / DENY，迁移完成后删除
can_query             # 旧字段，迁移完成后删除
can_export            # 旧字段，迁移完成后删除
can_view_sql          # 旧字段，迁移完成后删除
discover_effect       # ALLOW / DENY / UNSET
query_effect          # ALLOW / DENY / UNSET
export_effect         # ALLOW / DENY / UNSET
view_sql_effect       # ALLOW / DENY / UNSET
manage_effect         # ALLOW / DENY / UNSET
department_scope      # CURRENT / CURRENT_AND_CHILDREN，仅部门授权使用
granted_by
granted_at
expires_at
grant_reason
```

`access_effect + can_*` 不能表达“允许 QUERY、明确拒绝 EXPORT”在多角色之间的冲突。迁移规则固定为：旧记录 `access_effect=DENY` 时所有 action 回填 DENY；旧 ALLOW 记录中 boolean=true 回填 ALLOW，boolean=false 回填 UNSET 而不是 DENY。V2 Resolver 按每个 action 独立执行“任一 DENY > 任一 ALLOW > 默认拒绝”。影子对账完成后再删除旧字段。

### 36.2 `datasource_access_policy`

```text
id
datasource_id
subject_type
subject_id
table_name
column_name
resource_fingerprint   # 用于识别同名重建或语义漂移
granted_snapshot_id
include_future_resources
permission_action     # DISCOVER / QUERY / EXPORT / MANAGE；MASK/UNMASK 作用于结果数据
access_type           # ALLOW / DENY / MASK / UNMASK；UNMASK 仅由审批生成
column_scope_mode     # 表级策略使用 ALL_EXCEPT_DENIED / ALLOWLIST
mask_strategy
mask_enforcement      # MANDATORY / OVERRIDABLE，仅 MASK 使用
row_filter_dsl        # 新结构化条件；旧 row_filter_expression 仅迁移期读取
row_filter_kind       # SCOPE / MANDATORY
priority
valid_from
valid_until
time_schedule          # weekdays / hours / timezone
department_scope
created_by
created_at
updated_at
```

字段和索引要求：

- `subject_type`、`permission_action`、`access_type`、`row_filter_kind` 使用 Java enum + DTO 校验，数据库仍使用可读字符串；
- UNMASK 必须是 USER + 精确列 + 有效期 + 审批关联，普通策略 CRUD 不允许直接创建；
- 表/列名创建策略时必须存在于当前发布快照，执行时仍需再次按活动快照校验；
- `valid_until >= valid_from`，时间计划必须在写入时完成结构校验；
- 为 `datasource_id + subject_type + subject_id`、`datasource_id + table_name + column_name` 和有效期清理建立普通索引；
- 不创建数据库外键。

---

## 37. 前端权限中心最终需要支持

**[现状]** 仓库迁移中能确认的功能权限码包括：

```text
*
user:manage
role:view
role:manage
department:manage
datasource:manage
metadata:manage
knowledge:manage
field-tag:manage
feedback:review
security:manage
prompt:manage
prompt:approve
system:ai-config:view
system:ai-config:manage
```

当前前端守卫或 Controller 还引用了 `admin:view`、`skills:manage`、`field:manage`、`audit:view` 等权限码，但仓库 Flyway 迁移中未找到对应初始化记录；`query:use` 也尚未成为正式功能权限。这些都必须进入 B0 对账，不能只在前端数组中继续追加字符串。

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

信息架构已经在轨道 A 冻结，不再重新讨论菜单层级。权限必须消费现有 7 个一级业务域、工作台和 19 个二级工作区：

| 一级业务域 | 工作区 | 路由 | 建议查看权限 |
|---|---|---|---|
| 工作台 | 工作台 | `/admin/workbench` | `admin:workbench:view` |
| 数据接入 | 数据源 | `/admin/data-sources` | `datasource:view` |
| 数据接入 | 采集任务 | `/admin/collections` | `metadata:collect:view` |
| 数据资产 | 资产目录 | `/admin/assets` | `metadata:view` |
| 数据资产 | 版本发布 | `/admin/releases` | `metadata:publish:view` |
| 数据治理 | 治理总览 | `/admin/governance` | `governance:view` |
| 数据治理 | 问题中心 | `/admin/governance/issues` | `governance:issue:view` |
| 数据治理 | 规则与状态 | `/admin/governance/rules` | `governance:rule:view` |
| 数据治理 | 字段治理 | `/admin/governance/fields` | `governance:field:view` |
| 语义中心 | 业务术语 | `/admin/semantics/glossaries` | `glossary:view` |
| 语义中心 | 语义知识 | `/admin/semantics/knowledge` | `knowledge:view` |
| 语义中心 | Prompt 策略 | `/admin/semantics/prompts` | `prompt:view` |
| 权限与组织 | 授权管理 | `/admin/access` | `security:view` |
| 权限与组织 | 访问审批 | `/admin/access/approvals` | `security:approval:view` |
| 权限与组织 | 组织与角色 | `/admin/access/organization` | `organization:view` |
| 运营与平台 | 查询分析 | `/admin/operations/queries` | `audit:view` |
| 运营与平台 | 数据血缘 | `/admin/operations/lineage` | `lineage:view` |
| 运营与平台 | 运行监控 | `/admin/platform/runtime` | `system:runtime:view` |
| 运营与平台 | 操作日志 | `/admin/platform/operation-logs` | `operation-log:view` |
| 运营与平台 | AI 配置 | `/admin/platform/ai` | `system:ai-config:view` |

上表是目标命名基线；在写数据库迁移前必须生成一份“现有权限码 → 目标权限码 → Controller 方法 → 前端路由/Tab/按钮”的完整矩阵并逐项评审，避免同义权限码并存。查看和写操作至少分开，例如：

```text
datasource:view / datasource:manage
metadata:view / metadata:collect / metadata:publish
governance:issue:view / governance:issue:manage
knowledge:view / knowledge:manage / knowledge:approve / knowledge:publish
prompt:view / prompt:manage / prompt:approve
security:view / security:manage / security:approve
organization:view / user:manage / role:manage / department:manage
system:ai-config:view / system:ai-config:manage
```

前端实施规则：

1. `ADMIN_WORKSPACES` 增加工作区查看权限，侧栏只显示至少拥有一个可访问工作区的业务域；
2. 路由 `meta` 声明查看权限，`guards.ts` 正式消费，不能继续只判断“是否具有任一后台权限”；
3. 页面 Tab 声明自己的查看权限，无权 Tab 不渲染，URL 直达时跳到首个有权 Tab 或显示 403；
4. 新建、编辑、删除、审核、发布、导出、查看 SQL 等操作分别检查操作权限；
5. 后端 Controller/Service 使用同一权限码强制校验，前端隐藏按钮不能替代后端鉴权；
6. `*` 只作为功能权限通配符。轨道 B 的目标是让超级管理员也通过明确的数据授权访问业务数据；切换前需为现有管理员回填数据源授权，避免锁死；
7. 无任何可访问后台工作区的用户访问 `/admin/*` 时返回查询端或 403，不能进入一个全空壳后台；
8. 详情路由继承所属工作区查看权限，资源级权限由后端再次校验。

---

## 38. 最终完整权限链路

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

## 39. 最终职责

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

## 40. 最终原则

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

---

## 41. 当前实现基线与目标差异

| 领域 | [现状] | [目标] | 是否需要改造 |
|---|---|---|---|
| 功能权限 | `sys_permission` + 角色权限已存在，Controller 中散落 `hasAnyAuthority` | 权限码矩阵统一，工作区/Tab/操作和后端端点成对消费 | 是 |
| 前端守卫 | 只要命中任一后台权限即可进入全部 `/admin/*` 路由 | 路由、侧栏、Tab、按钮按各自权限判断 | 是 |
| 超级管理员 | `*` 同时放开后台和数据源查询 | `*` 只通配功能权限，业务数据仍需显式授权 | 是，先回填再切换 |
| 用户部门 | 一个 `department_id` | 轨道 B 继续保持一个主部门 | 否 |
| 部门继承 | 自动读取所有父部门授权 | 显式区分 CURRENT / CURRENT_AND_CHILDREN | 是 |
| 数据源授权 | USER 可覆盖 ROLE / DEPARTMENT，角色内 DENY 优先 | 任一有效 DENY 优先；无 ALLOW 默认拒绝 | 是 |
| Action 冲突 | 一个全局 accessEffect + 三个 boolean，无法表达按 QUERY / EXPORT / VIEW_SQL 分别 DENY | 每个 action 使用 ALLOW / DENY / UNSET 独立决策 | 是 |
| 表权限 | 无表级 ALLOW 时可能是 `UNRESTRICTED` | 普通用户只能得到明确 ALLOWLIST | 是 |
| 时间计划 | 非法 DENY 保持生效，但非法 ALLOW 当前仍可能参与计算 | 非法 DENY 生效、非法 ALLOW 无效，统一 fail-closed | 是 |
| 策略有效期 | 细粒度策略表已有 validFrom / validUntil / timeSchedule，但创建 DTO 和前端不能完整配置 | 写入、读取、校验、展示和解析闭环 | 是 |
| 列权限 | DENY、MASK 已能传给 Python；列 ALLOW 当前被忽略 | 增加 ALL_EXCEPT_DENIED / ALLOWLIST，固定 DENY > MASK > 原值且冲突可解释 | 是 |
| 行权限 | SQL 片段校验后，多来源直接 AND | 结构化 DSL；SCOPE OR、MANDATORY AND | 是 |
| 治理联动 | 已排除活动快照中 BLOCKED / DEPRECATED 表列 | 继续作为不可绕过硬约束 | 保留并补测 |
| RAG | 检索请求没有权限白名单 | 首次检索、相邻扩展、fallback、Schema Linking 全部先过滤 | 是 |
| SQL 安全 | Python 已有表白名单、列拒绝、行过滤 AST 改写 | 保留为执行前第二道硬校验 | 保留并补测 |
| 脱敏 | Python 标记输出字段，Java最终脱敏 | 保持该职责边界，补表达式/别名/子查询覆盖 | 补强 |
| 结果持久化 | Python 返回的结果数据先写入 `query_task.result_data`，读取 VO 时再由 Java 脱敏 | 持久化前完成 Java 最终脱敏，普通查询结果表不保存未授权原值 | 是 |
| 导出 | 前端根据 `canExport` 禁用本地导出按钮 | 官方导出能力前后端成对鉴权；承认已返回浏览器的数据无法靠按钮阻止复制 | 补强 |
| 访问审批 | “申请查看原值”通过后只生成 USER ALLOW，实际上不能解除 MASK | 区分 QUERY_ACCESS / UNMASK，增加取消、幂等、策略关联和完整解释 | 是 |
| 缓存 | PermissionCalculator 使用进程内 Caffeine | Redis-only，失败回源数据库且不放宽 | 是 |
| 权限预览 | 已有数据源最终决策端点 | 返回完整 reasonCode、命中规则和有效期 | 补强 |

这张表是轨道 B 的范围基线。开发过程中发现新增差异时，应先更新本表，再决定是否纳入当前阶段。

---

## 42. 目标执行链路

### 42.1 后台功能访问

```text
登录
→ Java /api/auth/me 返回功能权限码
→ 前端过滤一级域和二级工作区
→ 路由守卫检查工作区查看权限
→ Tab / 按钮检查查看或操作权限
→ Java Controller / Service 再次检查同一权限码
→ 记录写操作审计
```

功能权限只决定“能做某类事”，数据权限继续决定“能对哪些数据源/资产做”。因此后台列表、详情、搜索、统计和批量接口都必须在 Java 查询层应用 DISCOVER / MANAGE 数据范围：

- 列表的 records 与 total 使用同一权限过滤条件，不能先查全量再由前端隐藏；
- 详情、编辑、删除、发布和审批根据资源所属数据源再次校验，防止替换 path/body ID 越权；
- 批量操作逐项校验，采用项目既定的原子或部分成功语义并返回真实结果；
- 工作台聚合数字只统计当前用户可见资源，不能通过数量侧信道泄露无权对象。

### 42.2 智能问数

```text
用户选择数据源并提问
→ Java 校验 query:use
→ Java 校验数据源 readiness
→ Java Permission Resolver 计算请求级权限快照
→ 无 QUERY 或空表白名单：直接 403，不调用 Python/LLM
→ Java 把权限快照发送给 Python
→ RAG 在任何 LLM 调用前过滤无权 chunk
→ SQL 生成
→ sqlglot 提取全部表、列、JOIN、子查询和 CTE
→ 表白名单与列 DENY 二次校验
→ AST 注入行级条件
→ 只读沙箱执行
→ Python 返回 maskedFields
→ Java 最终脱敏并持久化结果/审计
→ 前端按 canViewSql / canExport 控制展示与操作
```

拒绝必须尽可能早发生，但早期拒绝不能替代后续防线。

### 42.3 历史会话与结果恢复

Java 已持久化会话、消息、任务结果和长期摘要。轨道 B 必须定义“执行时权限”和“查看时权限”的交集：

1. 查询完成时保存执行时 `decisionVersion`、数据源、快照、使用表列、脱敏字段和能力标记。
2. `result_data` 在持久化前按执行时策略脱敏，之后即使用户获得更宽权限，也不能把历史结果重新还原成原值。
3. 用户打开历史会话时按当前权限重新检查数据源、表列、VIEW_SQL 和 EXPORT；当前权限更严格时隐藏结构化结果/SQL，只保留不泄露数据的状态说明。
4. 临时授权到期或权限被撤销后，不继续展示依赖该授权的历史数据，也不把其内容放入新的 conversation history / summary。
5. 权限收紧不直接删除历史审计事实；审计记录与用户可见历史采用不同 VO 和权限边界。
6. 会话仍必须校验 `conversation.user_id + datasource_id`，不能只凭 conversationId 读取。

---

## 43. Permission Resolver 规范

### 43.1 输入

```text
userId
datasourceId
activeSnapshotId
action                 # DISCOVER / QUERY / EXPORT / VIEW_SQL / MANAGE
resource               # datasource / table / column / metric
requestTime
```

### 43.2 计算步骤

1. 校验用户、角色、主部门、数据源和活动快照均处于可用状态。
2. 收集 USER、ROLE、DEPARTMENT 及满足继承范围的父部门主体。
3. 加载这些主体在当前数据源上的有效 `datasource_access`。
4. 若命中任何有效数据源 DENY，返回 `DENY_EXPLICIT`。
5. 若没有至少一条允许当前 action 的数据源 ALLOW，返回 `DENY_NO_GRANT`。
6. 加载当前数据源的有效细粒度策略，并按主体、时间、部门范围过滤。
7. 从当前发布快照取得真实表/列集合；没有发布快照返回 `DENY_NO_PUBLISHED_SNAPSHOT`。
8. 应用治理硬阻断，移除 BLOCKED / DEPRECATED 表列。
9. 应用表级 DENY，再应用表级 ALLOW；普通用户最终始终得到 ALLOWLIST。
10. 按每张表的 `columnScopeMode` 计算允许列，再应用列级 DENY、MASK 和有效 UNMASK。
11. 按 `SCOPE OR`、`MANDATORY AND` 构造结构化行策略。
12. 生成可序列化 PermissionContext、决策解释和 `decisionVersion`。

### 43.3 决策原因码

至少统一以下稳定原因码，前端只翻译原因码，不解析后端中文消息：

```text
ALLOW_EXPLICIT
ALLOW_SUPER_ADMIN_COMPAT       # 仅迁移期开关，完成后删除
DENY_USER_DISABLED
DENY_DATASOURCE_DISABLED
DENY_NO_PUBLISHED_SNAPSHOT
DENY_GOVERNANCE_BLOCKED
DENY_EXPLICIT
DENY_NO_GRANT
DENY_TABLE_NOT_ALLOWED
DENY_COLUMN_NOT_ALLOWED
DENY_POLICY_INVALID
DENY_PERMISSION_EXPIRED
DENY_PERMISSION_REVOKED
```

### 43.4 幂等与时效

- 同一用户、数据源、快照和 `decisionVersion` 的输入应得到等价结果；
- 权限上下文必须冻结到单次查询任务，任务执行中不能一半使用旧权限、一半使用新权限；
- Python 不回调 Java 重新计算权限。临时权限带 `permissionExpiresAt`，SQL 执行前再次检查是否过期；
- 普通权限变更对新任务立即生效。紧急撤权如需影响进行中任务，由 Java 权限变更事件主动取消匹配的活动任务，Python 走已有取消检查，不新增第二套权限查询；
- 长任务取消、超时和失败不能把权限上下文复用到下一次任务。

---

## 44. 接口与跨服务契约

### 44.1 继续保留的管理接口

```text
GET    /api/admin/datasource-access
POST   /api/admin/datasource-access
PUT    /api/admin/datasource-access/{id}
DELETE /api/admin/datasource-access/{id}
GET    /api/admin/datasource-access/decision

GET    /api/admin/access-policies
POST   /api/admin/access-policies
POST   /api/admin/access-policies/batch
PUT    /api/admin/access-policies/{id}
DELETE /api/admin/access-policies/{id}

GET    /api/admin/access-approvals
POST   /api/admin/access-approvals
POST   /api/admin/access-approvals/{id}/review
```

改造要求：

- 更新接口中的原始 `Map` 和数据库 Entity 入参改为 DTO + `@Valid`；
- 创建、更新、审批接口支持幂等或乐观锁，重复提交不能生成重复策略；
- 权限预览返回数据源、表、列、行、脱敏、来源、原因码和有效期，不只返回三个 boolean；
- 普通用户只能查看和操作自己的申请，审批人权限由后端强制校验；
- 申请 DTO 明确 `requestType = QUERY_ACCESS / UNMASK`；UNMASK 只允许精确列和可覆盖 MASK；
- 审批记录新增 `generated_policy_id` 或等价关联，过期清理按关联 ID 删除，不能按宽泛条件误删其他临时授权；
- 增加申请人取消 PENDING 申请的接口；是否允许撤回已生效授权需另行确认。

### 44.2 Java → Python 查询契约

目标 `userPermissions` 至少包含：

```json
{
  "decision": "ALLOW",
  "decisionVersion": "opaque-version",
  "permissionExpiresAt": null,
  "tableScopeMode": "ALLOWLIST",
  "allowedTables": ["orders"],
  "columnScopeModes": {"orders": "ALLOWLIST"},
  "allowedColumns": {"orders": ["id", "amount", "customer_phone"]},
  "deniedColumns": ["orders.cost_price"],
  "rowPolicies": [
    {
      "tableName": "orders",
      "scopeConditions": [],
      "mandatoryConditions": []
    }
  ],
  "maskColumns": [
    {
      "tableName": "orders",
      "columnName": "customer_phone",
      "maskType": "PHONE"
    }
  ]
}
```

说明：示例只表示结构，不代表当前接口已经支持 `decisionVersion` 和结构化 `rowPolicies`。迁移期可同时接收旧 `rowFilters`，但 Java 只能发送一种权威格式，Python 不得把两套规则叠加执行。

### 44.3 RAG 检索契约

`RetrieveRequest` 增加请求级权限过滤条件：

```text
tableScopeMode = ALLOWLIST
allowedTables
columnScopeModes
allowedColumns
deniedColumns
decisionVersion
```

过滤规则：

- chunk 的主表和 `related_tables` 必须全部在允许表集合内；
- chunk 的 `related_columns` 命中 DENY，或不在该表的列 ALLOWLIST 时整块排除，除非能够可靠重建并删除无权字段描述；
- 元数据不完整、无法证明安全的 chunk 采用 fail-closed，不发送给 LLM；
- 相邻 chunk 扩展和 fallback 使用同一个过滤函数，不能各写一套近似逻辑；
- 允许表较少时可下推 Milvus 过滤；允许列表过大时采用有界 over-fetch + Python 强制后过滤和受控重试，避免构造超长 Milvus 表达式；无论是否下推，进入 LLM 前的应用层过滤不可省略；
- 记录过滤前后数量和原因码，但日志不输出敏感 chunk 全文。

### 44.4 HTTP 语义

- 未登录：401；
- 已登录但无功能或数据权限：403；
- 资源不存在：404；
- 乐观锁/重复审批冲突：409；
- DTO 或策略表达式不合法：422 或项目统一的参数错误码；
- 内部令牌错误：403；
- 权限服务内部失败：不得降级为允许，应返回明确失败并停止查询。

---

## 45. 数据迁移与兼容策略

权限系统不能大爆炸式切换，采用 Expand → Backfill → Verify → Switch → Contract：

### 45.1 Expand

1. 在现有表上增加目标字段、索引和默认值，不删除旧字段。
2. 新增目标功能权限码和角色映射，但前端暂不隐藏现有入口。
3. 增加新 DTO、Resolver V2 和权限解释输出，保留旧读路径。
4. 增加功能开关：`permission.resolver-v2-enabled`、`permission.frontend-enforcement-enabled`、`permission.rag-filter-enabled`。开关名称以实现时配置规范为准。

迁移版本号在开发开始时按仓库最新 Flyway 版本顺延，不在本文预占固定编号。`后续开发.md` 曾把 V53 用作未来 `alert_history` 示例，因此实现时必须统一重新编号，避免重复迁移。

### 45.2 Backfill

1. 把当前 `*` 管理员需要保留的数据访问能力回填为明确的数据源 ALLOW。
2. 按“旧 DENY → 各 action DENY；旧 ALLOW 的 true → ALLOW、false → UNSET”回填 action effects，并对账派生 boolean。
3. 现有 `datasource_access_policy` 的 ALLOW / DENY 统一回填 `permission_action=QUERY`；MASK 按结果策略迁移，不猜测 DISCOVER / EXPORT / MANAGE。
4. 为现有部门授权回填 `department_scope`，默认值必须基于当前真实继承结果生成并人工复核。
5. 为现有表级放开行为生成显式 `ALLOW *` 或具体表 ALLOW，避免切换默认拒绝后误锁用户。
6. 将可安全解析的 `row_filter_expression` 转换为 DSL；无法转换的标为待处理并阻止 V2 切换。
7. 检查重复、冲突、过期和引用不存在资源的策略，输出对账报告，不静默删除。

### 45.3 Verify

在影子模式中同时计算 V1/V2：

```text
用户
数据源
功能权限
表集合
拒绝列
脱敏列与策略
行策略
canExport
canViewSql
最终原因码
```

差异必须分类为“预期收紧、预期放开、迁移错误、算法错误”。未解释差异不允许进入 Switch。

### 45.4 Switch

1. 先切 Java Resolver 和 SQL 执行前校验；
2. 再切 RAG 权限过滤；
3. 再切前端路由/Tab/按钮消费；
4. 最后关闭超级管理员数据权限兼容开关。

每一步都必须可独立回滚，不能一次发布后同时改变数据库、Java、Python和前端全部语义。

### 45.5 Contract

稳定运行并完成真实验收后，才允许清理旧字段、旧 DTO、旧前端分支和兼容开关。删除前保留数据库备份和 Git 可追溯迁移记录。

---

## 46. 分阶段开发顺序

### B0：权限清单与决策冻结

- 生成 Controller API、前端路由、Tab、按钮和现有权限码清单；
- 完成现有权限码到目标权限码的映射；
- 冻结 DENY、默认拒绝、超级管理员、部门继承、行策略合并和 MASK 冲突规则；
- 明确普通用户“我的申请”入口与审批人范围；
- 产出数据库回填和回滚方案。

完成条件：没有待开发人员自行猜测的核心权限语义。

### B1：功能权限后端收口

- 新增/补齐查看、管理、审核、发布、导出等权限码；
- Controller 和关键 Service 使用统一权限码；
- 清除同义、过宽或只靠类级 `security:manage` 的端点；
- 保留 `*` 的功能通配语义；
- 增加端点权限参数化测试。

### B2：数据权限 Resolver V2

- 扩展现有两张权限表，不建立第二套永久并行模型；
- 实现明确的 DENY / ALLOW / 默认拒绝算法；
- 实现部门范围、时间条件、MASK 冲突和结构化行策略；
- 实现通配授权的未来资源开关、快照发布后的资源一致性检查和规范名称匹配；
- 将权限缓存迁移到 Redis；
- 在写入 `query_task.result_data` 前执行 Java 最终脱敏；如未来确需保存原值，必须使用独立加密存储、单独权限和审计，不得复用普通结果列；
- 完成回填、影子计算和差异报告；
- 扩展权限预览接口。

### B3：Python 权限执行与 RAG 过滤

- 扩展 Pydantic 权限契约；
- 首次检索、相邻扩展、fallback、Schema Linking 共用权限过滤器；
- conversation history / summary、glossary、few-shot、SQL 自校正和图表预览接入统一 Context Firewall；
- SQL Validator 覆盖别名、JOIN、CTE、子查询、星号、未限定列；
- 行策略使用 AST 构建；
- Python 只返回脱敏字段映射，Java 保持最终脱敏。

### B4：前端正式消费权限

- `ADMIN_WORKSPACES`、路由 meta、侧栏、Tab 和按钮接入权限；
- 所有无权直达路由有确定的 403/跳转行为；
- VIEW_SQL / EXPORT 与查询结果页真实联动；
- `canExport` 只约束产品提供的导出功能；数据已经返回浏览器后无法从技术上禁止复制，若业务要求严格防外泄，需要另行设计服务端导出、水印和 DLP，不在本阶段伪装成已解决；
- 授权管理页展示最终决策解释、冲突和有效期；
- 历史会话和结果恢复按执行时权限与当前权限交集展示；
- 普通用户申请入口按产品确认结果落地。

### B5：审批、审计和失效闭环

- 审批幂等、乐观锁、生成策略关联、取消和到期清理完整；
- 权限、组织、治理和快照事件正确失效 Redis；
- 权限预览、RAG 过滤、SQL 拒绝、行改写、脱敏和导出均可审计；
- 审计失败策略明确：安全决策日志失败不得静默放宽权限。

### B6：真实迁移与验收

- 真实 MySQL 执行迁移并完成回填对账；
- Java、Python、前端自动化通过；
- 使用非超级管理员角色完成浏览器权限矩阵验收；
- 使用真实 Milvus 验证无权 chunk 不进入 LLM 上下文；
- 保存接口结果、数据库决策、日志和桌面截图证据；
- 最后关闭兼容开关并更新状态文档。

不得跳过 B0 直接从前端隐藏菜单开始，也不得用 `*` 超级管理员完成 B6 的权限隔离验收。

---

## 47. 测试矩阵

### 47.1 Java 单元测试

至少覆盖：

- 无授权默认拒绝；
- QUERY / EXPORT / VIEW_SQL / DISCOVER / MANAGE 各自按 DENY > ALLOW > UNSET 计算，互不串权；
- USER ALLOW 不能覆盖 ROLE / DEPARTMENT DENY；
- 父部门 CURRENT 不向子部门继承；
- CURRENT_AND_CHILDREN 正确继承且组织循环 fail-closed；
- 过期、未来生效、工作日、跨午夜时间计划；
- 时间计划非法时 DENY 有效、ALLOW 无效；
- ALLOW * 减去表 DENY；
- 列 ALL_EXCEPT_DENIED 与 ALLOWLIST 语义明确，列 ALLOW 不会隐式授予表权限；
- 表 DENY、列 DENY、MASK 的固定优先级；
- MANDATORY_MASK 不可解除，OVERRIDABLE_MASK 只有有效审批 UNMASK 才能解除；
- 多 SCOPE 行范围 OR，多 MANDATORY 条件 AND；
- 治理 BLOCKED / DEPRECATED 不可授权和审批；
- 审批重复提交、并发审批、取消、到期只删除关联策略；
- ROLE / DEPARTMENT 变更使所有受影响用户 Redis 缓存失效；
- Redis 故障时数据库回源，数据库故障时拒绝而不是放行；
- 决策解释返回正确命中来源和原因码；
- Controller 每个端点的权限码和 `*` 功能通配。
- 后台列表、total、详情、统计和批量操作都按 DISCOVER / MANAGE 数据范围过滤，替换资源 ID 不能越权。

### 47.2 Python 单元测试

至少覆盖：

- 空 ALLOWLIST 拒绝全部表；
- JOIN、CTE、子查询中任一无权表导致拒绝；
- 表别名、未限定列和 `SELECT *` 不绕过列权限；
- 列 ALLOWLIST 为空时拒绝查询；`SELECT *` 继续按现有安全规则拒绝，不能借星号绕过列白名单；
- RAG 主 chunk、相邻 chunk、fallback 使用同一权限过滤；
- 多表 Join Path 有一张无权表时整块排除；
- denied column 不出现在 LLM Schema 上下文；
- few-shot 中含额外无权表/列时整条排除；用户级候选不会被其他用户直接召回，共享示例已审核并移除敏感字面量；glossary 关联资源按权限过滤；
- conversation history / summary 在撤权和切换快照后不进入新 Prompt；
- 图表 LLM 只收到已脱敏预览，不收到 MASK 字段原值；
- 行策略 AST 在已有 WHERE、JOIN、子查询下语义正确；
- LEFT / RIGHT JOIN 注入行条件后不被意外改写成 INNER JOIN，同表多别名分别受限；
- MASK 能追踪别名、表达式、派生表和 CTE，并由 Java 最终脱敏；
- SQL 自校正重试不能丢失权限上下文；
- 权限上下文缺失、版本过期或格式非法时 fail-closed。

### 47.3 前端单元测试

至少覆盖：

- 业务域和工作区按权限过滤；
- 路由 meta 拒绝无权直达；
- Tab 默认值不会落到无权 Tab；
- 写按钮、审批、发布、导出、SQL 展示分别按权限控制；
- `*` 只通配功能权限展示，不在浏览器构造数据权限；
- 权限刷新后导航立即更新；
- 403、权限到期和任务中途撤权有明确反馈。

### 47.4 集成与安全测试

- Java → Python 权限契约字段一致；
- 数据源 readiness、Permission Resolver、RAG、SQL、脱敏和审计全链路；
- 当前权限收紧后，历史消息、任务结果、SQL、导出和长期摘要按“执行时与查看时权限交集”处理；
- 绕过前端直接调用 API 仍返回 403；
- 请求参数篡改 userId / datasourceId / tableName 不越权；
- 并发撤权后新任务立即拒绝；紧急撤权按 Java 主动取消规则停止匹配的进行中任务；
- 日志、SSE 和错误响应不泄露无权 Schema、行条件敏感值或连接信息。

---

## 48. 真实验收角色与场景

至少准备以下非生产测试角色，授权数据使用专用夹具：

| 角色 | 用途 |
|---|---|
| 普通问数用户 | 只进入查询端，只能查询一个明确授权数据源 |
| 部门用户 | 验证父子部门继承和行范围 |
| 跨部门分析师 | 验证多角色/多授权范围 OR 合并 |
| 数据治理人员 | 能治理指定数据源，但不能管理组织或 AI 配置 |
| 数据安全管理员 | 能管理授权和审批，但不默认拥有业务数据原值 |
| 审计人员 | 只读查询审计、血缘和操作日志 |
| 系统管理员 | 拥有功能 `*`，数据访问仍依赖明确授权 |

必须真实验证：

1. 普通用户看不到无权限后台入口，直达 URL 也无法访问 API。
2. 有后台查看权限但无管理权限时，页面可读、写操作不可用且后端拒绝。
3. 无数据源 QUERY 权限时，Java 在调用 Python 前拒绝。
4. 有数据源权限但只有部分表权限时，RAG 和 SQL 都看不到其他表。
5. 列 DENY 不进入 LLM 上下文，也不能通过别名、表达式、JOIN 或子查询访问。
6. MASK 字段返回值确实由 Java 脱敏，前端和持久化结果不含未授权原值。
7. 两个角色的行范围正确 OR，系统强制约束正确 AND。
8. QUERY_ACCESS 和 UNMASK 临时授权通过后分别生效，到期后新请求立即失效；UNMASK 不能绕过 DENY、治理阻断或 MANDATORY_MASK。
9. 撤销角色、移动部门、发布新快照后缓存正确失效。
10. 权限预览能解释允许/拒绝来源，与真实查询结果一致。
11. `canViewSql=false` 时 API 和前端都不暴露 SQL；`canExport=false` 时导出 API 也拒绝。
12. 系统管理员没有明确数据授权时不能问数，回填后的授权范围与迁移前预期一致。
13. 图表生成、few-shot、术语表、历史会话和长期摘要不会把无权 Schema 或待脱敏原值发送给 LLM。
14. 权限撤销或临时授权到期后，历史结果与 SQL 立即按当前权限收窄，但审计事实仍可由授权审计员查看。

---

## 49. 回滚与故障策略

1. 所有迁移先增后删；旧列至少保留到 B6 验收完成。
2. Resolver V2、RAG 过滤和前端消费使用独立开关，出现问题按相反顺序回滚。
3. 回滚只能恢复旧解析器，不能恢复已过期、已撤销或被治理阻断的权限。
4. Redis 故障回源数据库；数据库或权限计算不可用时停止查询，不能切到 UNRESTRICTED。
5. Python RAG 权限过滤异常时停止生成 SQL；不得用未过滤 fallback 保持“可用性”。
6. Java 最终脱敏失败时不返回结果数据，并保留可审计的失败原因。
7. Flyway 迁移失败时修复前向迁移，不手工篡改历史迁移 checksum。
8. 回滚、补偿和人工处置不得删除仍有效的权限和审批记录。

---

## 50. Definition of Done

只有同时满足以下条件，轨道 B 才能标记完成：

- 本文所有 [目标] 已实现，未实现项已明确移出轨道 B 并获得确认；
- 功能权限矩阵覆盖全部正式后台路由、Tab、按钮和 Controller 端点；
- 普通用户数据权限默认拒绝，DENY、部门继承、行策略和 MASK 语义与本文一致；
- Java、Python、前端使用同一份可版本化权限契约；
- RAG 的所有入口在 LLM 调用前完成权限过滤；
- SQL AST 二次校验、行改写和 Java 最终脱敏均通过安全测试；
- Redis 缓存与所有变更事件失效正确，故障时不放宽权限；
- 旧数据完成回填和影子对账，没有未解释决策差异；
- 自动化测试、真实 MySQL/Flyway、真实 Milvus、真实接口和非超级管理员浏览器验收分别通过；
- 验收证据保存到 `output/playwright/`，并记录对应 Git commit；
- `AGENTS.md`、`CLAUDE.md`、README、状态文档和后续开发队列按真实结果更新；
- 未把代码通过、服务启动或局部页面可见误写成全链路完成。

---

## 51. 本阶段不做

- 多主部门或矩阵组织；
- 一个连接下跨 Database / Schema 授权；
- 任意 SQL 文本形式的行策略编辑器；
- 让 Python 或前端持久化独立权限事实；
- 只在 Prompt 中描述权限而不做 AST 强制校验；
- 与权限无关的 RAG 全量重建、效果评测和告警执行功能；
- 为了“模型完整”而同时保留两套长期运行的数据权限表。

这些能力如果以后进入范围，应先更新本文和执行队列，再开始实现。
