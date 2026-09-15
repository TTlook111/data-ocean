# DataOcean 完整权限体系设计与开发指导

> 文档状态：轨道 B 的 B0 评审与决策基线；只有完成 B0 权限码矩阵、数据回填方案和核心语义冻结后，才能作为 B1～B6 的直接实施基线；尚未代表功能已完成
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

本文已给出这些内容的目标规则、候选契约和实施边界，但不代替 B0 必须产出的「现有权限码 → 目标权限码 → Controller 方法 → 前端路由 / Tab / 按钮」逐项矩阵和真实数据回填对账。在该矩阵和对账方案评审通过前，本文可直接指导 B0，不得跳过 B0 开始 B1 / B2 的批量改造。

开发时必须区分以下标记：

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
6. `BLOCKED`、`DEPRECATED` 和未发布对象不能被普通授权、临时审批或超级管理员用于正式问数；MANAGE 必须保留查看、修复和评审这些对象的能力，不得复用问数硬阻断将它们隐藏。
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

### 0.5 本次评审问题修订索引（2026-09-15）

下表只表示文档规则已修订，不表示 B0 产出、数据库迁移、代码或真实验收已完成。

| 评审问题 | 本文对应规则 |
|---|---|
| 问数硬阻断误伤治理管理 | 第 9、13.1、43 节：action 生命周期矩阵，MANAGE 保留草稿/阻断对象治理能力 |
| MASK 可被条件/表达式推断绕过 | 第 17.1、21.1、42 节：直接投影基线、推断拒绝、整份响应与模型预览防泄露 |
| 数据源准入、表通配和快照固化不一致 | 第 11、15、36、45 节：QUERY gate 与表范围分离，通配基线和未来资源显式控制 |
| 单 action Resolver 与全局结果 boolean 不一致 | 第 13、14、31、43、44 节：QueryPermissionContext / ActionDecision 与实际业务资源集决策 |
| 单一过期时间无法覆盖时效竞态 | 第 27、34、43.4、42 节：evaluatedAt / nextEvaluationAt 与 RAG/执行/响应前检查 |
| Row DSL 缺执行绑定和系统约束存储 | 第 19.1、36、44 节：SYSTEM CONSTRAINT、typed bindings、独立 permissionParameters |
| RAG metadata 无法证明资源引用完整 | 第 22、44.3、45 节：资源绑定、complete / manifest hash、历史复核与 fail-closed |
| 功能权限码仍有同义词和未冻结矩阵 | 第 4、37、46 节：统一命名输入、完整端点/Tab/操作矩阵及 B0 冻结门槛 |

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

功能权限示例必须使用第 37 节的目标命名，不再创建 `asset:view` / `permission:view` 等同义码：

```text
query:use
query:history:view

datasource:view
datasource:manage

metadata:view
metadata:collect:view
metadata:collect
metadata:publish:view
metadata:publish

governance:view
governance:issue:view
governance:issue:manage

security:view
security:manage
security:approval:view
security:approve

audit:view
audit:export

organization:view
user:manage
role:manage
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

**[实施]** 现有 `datasource`、快照表/列元数据和规范的 `datasource_id + table_name + column_name` 继续作为资源定位依据，配合 fingerprint 和对象连续性检查，而不把名称本身当成永久身份。QUERY 使用当前发布快照，MANAGE 使用实际管理对象所属快照/稳定 ID。`metadata_entity` 发布时会重建数据源实体，不能在未保证 ID 稳定前把权限永久绑定到其自增 ID。指标权限只有在指标实体、依赖关系和查询链路均正式落地后才启用，不能只加一个枚举值。

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

不同 action 的资源与校验时点必须明确，不得把数据源级的一个 boolean 直接当成所有表列的最终决策：

| Action | 作用资源 | 校验时点 | 多资源合并规则 |
|---|---|---|---|
| DISCOVER | 数据源、表、列、指标 | 列表、搜索、详情读取时 | 只返回具有 DISCOVER 或更高级 MANAGE 权限的资源 |
| QUERY | 数据源、表、列、行 | 问数任务创建前，SQL 生成后再次校验 | SQL 引用的所有表列均必须允许，任一 DENY 则拒绝整条 SQL |
| EXPORT | 已完成查询的实际表列集合 | 服务端导出请求发生时 | 使用「执行时资源 ∩ 当前有效权限」；任一实际资源不允许则整体禁止导出 |
| VIEW_SQL | 生成及最终执行 SQL 引用的实际表列集合 | SQL 已生成且准备返回时，历史查看时再校验 | 任一实际资源缺少 VIEW_SQL 则不返回 SQL |
| MANAGE | 数据源、草稿/已发布快照、表、列及治理对象 | 管理列表、详情和写操作时 | 逐资源校验；不因对象未发布、BLOCKED 或 DEPRECATED 而失去治理入口 |

`canExport` 和 `canViewSql` 是针对某个已知查询任务的派生结果，不是数据源的全局属性。必须等 SQL 实际业务表列集合已确定后由 Java 再计算，且分别同时满足 `query:export` / `query:sql:view` 功能码与 EXPORT / VIEW_SQL 数据范围；导出时还要按当前时间和当前权限重新校验。

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

DataResource 是逻辑树结构，但存储层不使用隐式继承。轨道 B 固定以下规则：

1. `datasource_access` 是 action 的数据源级准入门槛，QUERY 准入不自动表示对当前和未来全部业务表的永久授权。MANAGE 的管理对象继承使用第 13.1 节的独立规则，不能据此读取业务原值。
2. QUERY 通过数据源级门槛后，表范围必须由 `datasource_access_policy` 的精确表策略或表级通配策略给出。
3. `table_name='*'` 是「当前数据源下的表集合」特殊标识，不是物理表名；只能用于表级策略，此时 `column_name` 必须为 NULL。
4. 表级 ALLOW 向列的继承由该策略的 `column_scope_mode` 明确表达，不再使用未落库的模糊 `inherit=true`。
5. 子资源可以在父级 ALLOW 基础上继续收窄权限；父级 DENY 不能被子资源 ALLOW 放开。

例如，某个用户要查询 `sales_db` 的当前全部已发布表，需要同时存在：

```text
datasource_access: QUERY = ALLOW
datasource_access_policy: table_name = '*', permission_action = QUERY, access_type = ALLOW
```

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

表级通配授权必须明确是否覆盖未来资源：

- `include_future_resources = false`：授权时固化当前发布快照中的资源集合，新发布的表/列默认不自动获得权限；这是默认值。
- `include_future_resources = true`：未来新增资源也继承授权，只允许管理员在确认风险后显式选择，并记录审计。

`false` 模式下，通配策略的 `granted_snapshot_id` 必填，解析时读取授权时资源集合，再与当前活动快照取交集；不要求为每张表复制一条策略。授权时快照不存在或历史快照已不可读时必须拒绝创建/解析，不能退化为当前全量资源。`true` 模式必须为管理员显式选择，`granted_snapshot_id` 仍保存授权当时基线以便审计，但解析时使用当前活动快照。

精确表策略不使用 `include_future_resources`；它通过规范表名、`resource_fingerprint` 和授权快照共同识别原资源。数据源级 DENY 仍直接拒绝对应 action，不再进入表列解析。

活动快照发布后，Java 必须对权限资源做一致性检查：已删除/重命名对象标为失效或待处理，新对象按上述开关决定是否继承。不能因为策略只保存字符串名称，就把一个已删除后同名重建但语义不同的对象静默视为原资源。

表列匹配使用当前发布快照返回的规范名称和数据源方言规则。不得一律 `toLowerCase()` 后假设所有 MySQL 环境大小写语义相同；需要保存规范名并使用单独的比较键。`resource_fingerprint` 至少由数据源、规范表名、规范列名、资源类型与结构特征按稳定顺序生成；算法版本必须入库，避免算法升级被误判为资源篡改。

结构 fingerprint 不能单独识别“删除后按相同结构同名重建”。快照发布一致性检查还必须核对采集/变更事件中的删除与重建连续性；存在删除后重建记录，或无法证明原资源连续存在时，将精确策略标记为待重新授权，不能仅凭同名且 hash 相同自动恢复权限。该规则允许保守收紧，不声称仅靠结构摘要就能识别所有业务语义变化。

---

## 12. 权限冲突优先级

最终权限由 Permission Resolver 统一计算。

最终决策顺序固定为：

```text
对应 action 的生命周期门槛（QUERY 的未发布 / BLOCKED / DEPRECATED 硬阻断不套用到 MANAGE）
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

任何明确 DENY 都不能被低优先级 ALLOW 覆盖。该顺序按 action 独立计算，MANAGE 不能派生 QUERY，QUERY 也不能派生 EXPORT / VIEW_SQL。

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

统一实现不等于一个模糊的“全局权限上下文”方法。轨道 B 对外分为两类稳定能力：

```text
resolveQueryContext(userId, datasourceId, activeSnapshotId, requestTime)
authorizeAction(userId, datasourceId, snapshotId?, action, resourceSet, requestTime)
authorizeQueryTaskAction(userId, queryTaskId, action, requestTime)
```

- `resolveQueryContext` 为一次问数计算 QUERY 表列范围、行约束、MASK / UNMASK 和下一次必须重新计算的时间，并生成发送给 Python 的 `QueryPermissionContext`。
- `authorizeAction` 对 DISCOVER / MANAGE / EXPORT / VIEW_SQL 和已知资源集合做服务端决策。EXPORT / VIEW_SQL 不得在 SQL 实际表列集合尚未知晓时生成一个全局真值。
- 两类能力共用同一套主体收集、DENY 优先、时间解析、部门继承、资源匹配、解释和审计实现，不各自复制规则。

authorizeQueryTaskAction 是查询结果场景的入口包装：由 Java 读取任务所有者、执行时范围和可信业务引用，做执行时/当前权限交集后调用 authorizeAction；浏览器不能自行提交 usedResources 冒充任务血缘。

Permission Resolver 的每个输出必须同时包含“结果”和“解释”，至少能回答：

```text
decision: ALLOW / DENY
reasonCode
matchedGrantIds
matchedPolicyIds
decisionSource
matchedExpiresAt
evaluatedAt
nextEvaluationAt
```

解释信息用于管理员权限预览和审计，但不得把其他主体的敏感策略表达式返回给普通用户。

### 13.1 Action 与生命周期门槛

治理状态不能对所有 action 使用同一个通用前置条件：

| Action | 数据源启用/健康 | 活动发布快照 | BLOCKED / DEPRECATED | 未发布/草稿对象 |
|---|---|---|---|---|
| QUERY | 必须启用、健康且 readiness 通过 | 必须 | 不可绕过 | 不可问数 |
| EXPORT | 导出已保存结果不要求连接健康，但数据源仍须存在且启用、结果查看权限仍有效 | 按执行时与当前权限取交集 | 当前已阻断的资源不可导出 | 不可导出为正式问数结果 |
| VIEW_SQL | 不要求连接健康，但数据源须存在且启用 | 实际 SQL 资源必须能与当前发布资产确认连续性 | 任一命中则隐藏 SQL | 不可作为正式问数 SQL 展示 |
| DISCOVER | 数据源级可对已禁用/异常对象返回必要状态，不得返回凭据 | 普通资产发现只看已发布资产 | 有显式 DISCOVER 时可看已发布结构及不可用状态，不因此获得 QUERY | 只有同时具备 MANAGE 时才可查看管理视图 |
| MANAGE | 不要求健康或已启用 | 不要求 | 必须可见且可进入修复/评审流程 | 必须可见，否则无法完成采集、治理和发布 |

治理硬阻断的语义是“禁止进入问数、导出和模型上下文”，不是“让管理员看不到待治理对象”。

数据源级 MANAGE ALLOW 允许进入该数据源所属的连接配置、采集、草稿快照、治理、评审和发布工作流；精确管理范围如需收窄，使用该管理对象所属快照/稳定 ID 校验并应用 MANAGE DENY，不要求它先出现在活动发布快照中。管理 VO 可以返回治理所需结构和状态，但 MANAGE 本身不授予业务数据原值、采样值、SQL 查询或导出能力。

新建数据源时尚无目标资源可供前置授权：后端先校验 `datasource:manage` 创建权限，再在同一事务中为创建人保存显式 DISCOVER / MANAGE 准入记录；不自动授予 QUERY / EXPORT / VIEW_SQL。已有数据源的管理范围必须在 B0/回填中明确，不靠 `*` 或 QUERY 记录猜测 MANAGE。

---

## 14. QueryPermissionContext 与 ActionDecision

每次智能问数生成一份只服务于当前任务的 `QueryPermissionContext`。其他 action 使用 `authorizeAction` 结果，不把两类语义混成一个对所有场景都有效的全局对象。

例如：

```text
QueryPermissionContext

contractVersion

userId

datasourceId

snapshotId

roleIds

departmentIds

allowedTables

tableScopeMode

columnScopeModes

allowedColumns

deniedColumns

rowPolicies

maskColumns

queryDecision

decisionVersion

evaluatedAt

nextEvaluationAt

matchedExpiresAt
```

Spring Boot 是权限中心。

Agent 不自己重新发明另一套权限规则。

**[实施契约]** Java 生成请求级 `QueryPermissionContext` 后随 `/internal/query/execute` 发送给 Python。Python 模型字段保持 camelCase/snake_case 双向兼容，但新增字段必须同时更新 Java VO、Java client、Python Pydantic 模型和契约测试。`tableScopeMode` 只允许：

```text
ALLOWLIST    # allowedTables 为空表示拒绝全部表
UNRESTRICTED # 只允许受控的超级管理员兼容期使用，轨道 B 完成后不作为普通用户结果
```

`nextEvaluationAt` 是参与决策的有效期、待生效时间和周期时间计划的下一个可能变化点，不等同于某一条策略的 `expiresAt`。到达该时间后，冻结上下文失效，任务不得继续使用旧结果。`matchedExpiresAt` 只用于解释已命中授权的到期时间，不用它替代下一次重计算时间。

只有相关决策不存在任何未来时间变化点时 nextEvaluationAt 才可为 NULL；字段缺失/格式非法不能当成永久权限。时间均使用 UTC，服务时钟异常时拒绝安全敏感阶段，不用宽限时间延长授权。

ActionDecision 是 Java 服务端资源集合级输出，至少包含 action、规范化 resourceSet、decision、reasonCode、matchedGrantIds / matchedPolicyIds、evaluatedAt / nextEvaluationAt 和 decisionVersion。它只对该 action、该资源集合及该时间窗有效；queryTask 的 canExport / canViewSql 是相应 ActionDecision 再与功能码结合后的派生字段，前端不能自己重算。

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

多个授权来源的列范围不通过「先挑一个 `column_scope_mode`」合并，而是先把每个有效表级 ALLOW 解析成具体列集合，再取授权并集，最后减去任一来源的显式 DENY。因此：

- 某个有效来源为 `ALL_EXCEPT_DENIED` 时，该来源贡献其资源基线内的全部列；通配策略 `include_future_resources=false` 使用授权快照与活动快照的交集，true 才使用活动快照的全部列，随后统一减去 DENY；
- `ALLOWLIST` 来源只贡献它明确 ALLOW 的列；
- 不同来源的可访问列取并集，任一有效 DENY 在并集后统一删除；
- 治理硬阻断列在最后再次删除，不能被任何模式放开。

上述“按来源解析集合→并集→DENY 删除”规则也用于 DISCOVER / EXPORT / VIEW_SQL 的表列范围，但必须使用各自 permission_action，不能继承 QUERY 的 ALLOW。MANAGE 使用管理对象范围而不授予业务值。

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

priority 数值越小越优先，沿用当前项目语义。策略 CRUD 当前是即时写入而非发布生命周期：写入时发现同优先级冲突必须拒绝，解析器遇到遗留冲突降级为 FULL。结果类型/格式不符合 PHONE / EMAIL 等策略要求时同样 FULL 遮蔽，不能保留无法验证的原值片段。

**[职责边界]** Python 根据 SQL AST 识别输出列与物理敏感列的映射，并返回 `maskedFields`；Java 使用服务端策略对最终结果做脱敏。前端只展示“已脱敏”提示，不能接触原值后再遮盖。

### 17.1 MASK 列的 SQL 使用边界

MASK 不等于“可以任意计算，只要最后格式化输出”。仅跟踪 SELECT 输出别名并对结果应用 PHONE / EMAIL 等格式化，无法阻止通过谓词、分组、排序或布尔表达式推断原值。例如以下查询不会直接返回手机号，但仍可泄露敏感信息：

```sql
SELECT COUNT(*)
FROM customer
WHERE phone LIKE '138%';
```

轨道 B 首版固定为以下 fail-closed 规则：

1. MASK 列默认只允许作为直接投影列，由 Python 跟踪到输出名，Java 在持久化和返回前执行最终脱敏。
2. MASK 列出现在 `WHERE`、`JOIN ON`、`HAVING`、`GROUP BY`、`ORDER BY`、`DISTINCT`、窗口分区/排序、`CASE`、函数或算术表达式中时，默认拒绝。
3. 如业务需要对敏感列做聚合，必须单独定义经评审的安全操作白名单、最小分组基数/阈值和抑制规则；未完成该设计前不开放。
4. 复杂表达式不得直接套用原列的 PHONE / EMAIL 脱敏器；无法证明是安全直接投影时拒绝 SQL，不使用错误的格式化结果充当安全控制。
5. 查询文本、SQL 自校正、图表、推荐追问和长期摘要不得收到 MASK 列原始样例值或未脱敏结果。
6. 上述限制针对用户/模型生成的 SQL。Java 已审核且 Python 已验证的行权限 DSL 可以引用策略所需的隐藏列，用于强制隔离；该内部 AST 不构成用户列授权，参数值不进入 LLM。Python 必须分别记录业务 SQL 引用与权限注入引用，不能因可信策略引用了隐藏列而给用户开放该列。

UNMASK 有效时才可按原列权限处理，但仍受列 DENY、治理硬阻断、SQL 安全规则和当前时间二次校验限制。

Java 最终脱敏覆盖整份用户响应，而不只是 result_data：SSE 结果分块不得原样透传 Python 原值，chartConfig 内的 dataset/series、解释文本和可见历史消息也不得保留未授权原值。首版 Python 给图表 LLM 的预览直接移除所有需要 MASK 的结果列；不能移除且无法证明安全时使用本地确定性选图或不生成图表。Java 从脱敏结果绑定图表数据，不能在脱敏表格旁继续返回包含原值的旧 chartConfig。

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

本阶段只有一个主部门：`departmentIds` 仅是当前启用主部门 ID 的单元素列表，不包含父部门路径，也不表示矩阵组织成员关系。没有可用主部门时该变量不可解析，不转换成“所有部门”。

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

行范围和列范围不能在丢失授权关联后盲目做笛卡尔积。首版不支持任意单元格级权限：同一表多来源授权若同时具有不同列集合和不同 SCOPE 行范围，且无法证明至少一维一致，配置发布/Resolver 必须返回 DENY_POLICY_INVALID；不能把“角色 A 只看华东工资 + 角色 B 只看华南姓名”合成“两个地区都看工资和姓名”。需要该能力时另行设计按列关联行范围的执行模型。

行策略固定区分为：

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

允许的叶子操作符仅包含 `EQ`、`NE`、`GT`、`GE`、`LT`、`LE`、`IN`、`NOT_IN`、`IS_NULL`、`IS_NOT_NULL`；逻辑节点只允许 `AND`、`OR`。列名必须属于目标表当前发布快照，值必须参数化绑定。首版不支持函数、子查询、任意 SQL、跨表列引用和用户自定义变量。

### 19.1 Row Policy 存储和执行契约

`SCOPE` 策略继续使用 USER / ROLE / DEPARTMENT 主体。`MANDATORY` 表示不能被业务授权扩大或取消的系统约束，因此 `datasource_access_policy.subject_type` 在轨道 B 扩展支持 `SYSTEM`：

- `subject_type=SYSTEM` 只能与 `row_filter_kind=MANDATORY`、`access_type=CONSTRAINT`、`permission_action=QUERY` 组合，`subject_id` 为 NULL；它只贡献强制条件，绝不贡献数据源/表/列 ALLOW；
- SYSTEM 策略对指定数据源/表的所有查询生效，只能由具备专用安全权限的管理端点创建或修改；
- 普通主体的 `MANDATORY` 写入一律拒绝，避免一个角色把自己的限制伪装成系统规则；
- 如今后需要“只对某主体强制”，应新增明确的策略类型，不改写 SYSTEM 语义。

Java 是变量事实来源：它校验 DSL 结构和列归属，并把 `$currentUser.*` 解析成带类型的值。Python 不从字符串中替换变量，而是为每个值生成冲突安全的命名占位符（例如 `__do_perm_0`），通过 sqlglot AST 注入条件，并把绑定值作为独立 `permissionParameters` 传给执行器。`IN` / `NOT_IN` 的每个值都生成独立占位符。

DSL 比较叶子必须使用且只使用一种值来源（value / values / valueRef），IS_NULL / IS_NOT_NULL 不允许值字段，逻辑节点只能使用 op / conditions。允许标量类型固定为 LONG / DECIMAL / STRING / BOOLEAN / DATE / DATETIME；禁止对象和嵌套数组。变量缺失、EQ 等比较收到 NULL、IN / NOT_IN 空列表或类型不匹配均视为非法策略；ALLOW 失效，SYSTEM CONSTRAINT 则拒绝查询，不能跳过强制条件。首版限制逻辑深度 8、叶子 100、单个 IN 列表 1000 项，超限拒绝而非截断。

每张表还必须返回 `rowScopeMode=ALL_ROWS/FILTERED`：存在有效无行条件 ALLOW 时为 ALL_ROWS，scopePolicies 可为空但 mandatoryPolicies 仍生效；FILTERED 的 scopePolicies 为空表示无可访问行，不能用空数组表达无限制。

执行器目标契约必须同时携带：

```text
rewrittenSql
permissionParameters
```

不得把绑定值拼接回 SQL 文本。SQL 自校正只能修改模型生成的 SQL，不得改写行权限 DSL 或绑定参数；每次重试都必须重新走权限校验和 AST 注入。绑定值不发送给 LLM，不记录到普通日志。

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
6. 当前图表生成会把最多 20 行数据样本发送给 LLM。轨道 B 首版按第 17.1 节从预览中移除 MASK 结果列，无法安全处理时改为本地确定性选图/不出图；不得把待脱敏原值先发给模型、最后才对用户脱敏。
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
permission_metadata_complete
permission_resource_manifest_hash
```

当前 Milvus 已保存 `datasource_id`、快照/文档版本、`related_tables`、`related_columns`、`entity_ids` 和治理信息。轨道 B 优先基于稳定的表名/列名集合做请求级过滤；在 `metadata_entity` ID 生命周期稳定前，不把 `entity_ids` 作为唯一权限键。

`related_tables` / `related_columns` 不能只由宽松正则提取后就被当成完整的安全事实。知识文档发布前必须生成可验证的资源清单：

1. 从结构化 skills.md 来源、SQL 片段 AST、规范表列名和当前快照共同生成 chunk 的全部表列引用；
2. 清单内的每个标识符必须能解析到当前快照，未知、歧义或无法归属的引用阻止发布；
3. 验证通过后才写入 `permission_metadata_complete=true` 和按稳定顺序生成的 `permission_resource_manifest_hash`；
4. 上下文前缀、相邻 chunk 和 fallback 不得引入资源清单之外的表列名；
5. 旧向量没有完整标志，或 MySQL 快照与 Milvus 中的清单 hash 不一致时，对普通用户 fail-closed，不根据「看起来有 metadata」猜测它安全。

完整标志不是“正则没有发现未知标识符”就自动为 true。结构化语义单元必须显式绑定资源；自由文本不能可靠缩小引用范围时，保守绑定整个知识文档声明的全部表列集合，或保持 `permission_metadata_complete=false` 并阻止发布。B0 必须确认资源绑定编辑/审核入口与历史文档复核方案，不能把未经验证的旧正文直接回填为 complete。

MySQL 清单事实由 Java 提供：Java 可以随请求发送 expectedChunkManifestHashes；对未预载的召回 source_id，Python 在进入 LLM 前通过内部令牌调用 Java 的有界批量清单读取接口，按 datasourceId + activeSnapshotId + doc/version + source_id 获取已发布 hash。该接口只读取知识快照，不重算权限或返回原始权限表；读取失败/版本不匹配时排除 chunk 或终止检索，不能只拿 Milvus 自己的 hash 和自己比较。

资源引用完整不等于正文值安全：发布校验还必须移除敏感列原始样例值/业务字面量，保留类型、受控描述和合成示例。请求级 Firewall 对 MASK 列样例值再次删除；仅凭该列有 QUERY 权限不能把其原值通过 chunk、术语描述或 few-shot 送入 LLM。

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

解析器必须为每类决策分别计算 `nextEvaluationAt`：它是该决策相关的 `valid_from`、`valid_until` 和周期计划边界中，下一个可能改变结果的时刻。QueryPermissionContext 包含 QUERY、行/列约束、MASK / UNMASK 的变化点；EXPORT / VIEW_SQL 的 ActionDecision 保存各自变化点。不能用某一条临时授权到期时间代替整个决策时效，也不能将 EXPORT 的单独时间变化错误地当成 QUERY 自动到期。

例如“永久 QUERY + 10 点到期 UNMASK + 工作时段 EXPORT”中，查询上下文在 10 点失效，EXPORT 在其工作时段边界独立重新计算。需要同时返回能力时，Java 记录各 action 的 deadline，不能压成一个含义不明的全局过期时间。

任务在 `nextEvaluationAt` 前未完成时不继续使用旧上下文：Java 主动取消任务，Python 在 RAG 前、SQL 执行前检查该时间，Java 在最终脱敏、持久化和返回结果前再次检查。为了避免用旧决策错误返回原值，首版到期后直接终止当前任务，不在运行中热切换权限上下文。

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

**[目标]** 申请明确区分 `QUERY_ACCESS` 和 `UNMASK`。QUERY_ACCESS 按申请资源在同一事务中创建一组限时 USER ALLOW：需要时创建数据源 QUERY 准入记录，并创建精确表/列范围策略；不得只创建表策略却忘记数据源准入，也不得覆盖申请人既有的永久授权。UNMASK 只对 `OVERRIDABLE_MASK` 精确列生成临时 UNMASK，且不会顺带授予数据源或表 QUERY。

增加申请人取消 PENDING 申请、审批幂等/乐观锁和精确生成记录关联。申请表新增 `generated_grants` 或等价关联列表，保存每条生成记录的类型（DATASOURCE_ACCESS / ACCESS_POLICY）和 ID；每条生成记录反向保存 `approval_request_id`。单一 `generated_policy_id` 仅适用于确实只生成一条策略的 UNMASK，不能表达 QUERY_ACCESS 的授权组。到期处理按关联 ID 精确归档/撤销并保留审计事实，不按“用户 + 数据源 + 表 + 到期时间”的宽条件删除；有效期仍由 Resolver 实时判断，不依赖定时任务才失效。

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
resolveQueryContext(userId, datasourceId, activeSnapshotId, requestTime)

authorizeAction(userId, datasourceId, snapshotId, action, resourceSet, requestTime)

authorizeQueryTaskAction(userId, queryTaskId, action, requestTime)

getFunctionPermissions(userId)

getAuthorizedDataSources(userId)

getAuthorizedSchemas(userId)

getAuthorizedTables(userId)

getAuthorizedColumns(userId, tableId)

getRowPolicies(userId, tableId)

getMaskPolicies(userId, tableId)

hasPermission(userId, datasourceId, snapshotId, resource, action, requestTime)

explainDecision(userId, datasourceId, snapshotId, resourceSet, action, requestTime)
```

其中 `resolveQueryContext` 必须带 `datasourceId + activeSnapshotId + requestTime`，不能计算一个脱离数据源和发布快照的全局 QUERY 表权限。`authorizeAction` 对 MANAGE 数据源或未发布治理对象时允许 `snapshotId` 为空，但必须通过稳定数据源/对象标识解析真实资源，不得因没有活动快照而放弃资源级校验。

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

权限解析发生在 Java 发起 Python 请求之前。LangGraph 不新增一个会自行查询权限数据库的 `Permission Resolver` 节点；图内各节点只消费同一份不可变的请求级 `QueryPermissionContext`。

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

上图只表达权限检查的业务先后依赖，不要求把当前 Query Rewriter + Metadata Prefetch 的并行 fan-out 退化为串行。任何并行分支在使用 Schema、历史上下文或调用 LLM 前，仍必须消费同一份已校验且未超过 `nextEvaluationAt` 的权限快照。

权限上下文在整个 Workflow 中传递。

Java 侧调用顺序：

```text
认证用户
→ 校验 query:use
→ 校验数据源 readiness
→ Permission Resolver 计算并冻结 QueryPermissionContext
→ 调用 Python /internal/query/execute
```

---

## 34. 权限缓存

完整权限计算使用现有 `RedisTemplate<String, Object>` 缓存。

例如：

```text
permission:query-context:{userId}:{datasourceId}:{snapshotId}
permission:action:{userId}:{datasourceId}:{snapshotId-or-none}:{action}:{resourceSetHash}
```

`decisionVersion` 保存在缓存值和审计中，建议由参与决策的用户/角色/部门关系、有效授权/策略 ID 与更新时间、活动快照 ID 经过稳定排序后计算摘要。不能把一个尚未计算出来的 `decisionVersion` 放进读取缓存所需的 key，否则每次命中缓存前仍要完整计算权限。

摘要输入还必须包含 action/资源集合、SYSTEM CONSTRAINT 版本、组织/治理状态版本、规范名称/fingerprint 算法版本及当前时间有效窗标识；时间计划跨界后不能沿用旧 decisionVersion。Redis 缓存保存结构范围和策略模板，不保存解析后的敏感 bindings；Java 在创建请求级上下文时回源读取/解析需要绑定的值并校验 nextEvaluationAt，不能把带原始行条件值的完整执行载荷直接缓存。

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
6. 缓存 TTL 不得超过 `nextEvaluationAt - evaluatedAt`；跨过有效期或时间计划边界后必须重新计算。
7. `ActionDecision` 缓存 key 必须包含 action 和规范化后的完整资源集合 hash；不得把某个表的 EXPORT / VIEW_SQL 结果复用到另一个多表查询。

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
grant_source          # MANUAL / APPROVAL
grant_key             # 服务端生成：MANUAL，或 APPROVAL:{requestId}
approval_request_id   # APPROVAL 时必填，MANUAL 时为 NULL
granted_by
granted_at
expires_at
grant_reason
status                # ACTIVE / REVOKED；过期由时间实时判定，不依赖定时任务才生效
revoked_by
revoked_at
row_version           # 乐观锁
```

`access_effect + can_*` 不能表达“允许 QUERY、明确拒绝 EXPORT”在多角色之间的冲突。迁移只解释旧模型实际具有的 QUERY / EXPORT / VIEW_SQL：旧记录 `access_effect=DENY` 时这三个 action 回填 DENY；旧 ALLOW 记录中相应 boolean=true 回填 ALLOW，false 回填 UNSET 而不是 DENY。新增 DISCOVER / MANAGE 默认 UNSET，必须依据 B0 的管理范围/资产可见范围映射单独回填，不能从旧 ALLOW 或 DENY 猜测。V2 Resolver 按每个 action 独立执行“任一 DENY > 任一 ALLOW > 默认拒绝”。影子对账完成后再删除旧字段。

`datasource_access` 只负责 action 准入，不保存表范围、`include_future_resources` 或 `granted_snapshot_id`；这些信息必须位于下面的表级策略中。撤销使用 `status=REVOKED` 保留可追溯事实，不直接删除历史记录。

现有 `uk_datasource_subject(datasource_id, subject_type, subject_id)` 会阻止永久记录与临时审批记录并存，必须在 Expand 阶段迁移为普通查询索引，并新增 `(datasource_id, subject_type, subject_id, grant_key)` 唯一索引。MANUAL 汇总记录固定 grant_key=MANUAL，APPROVAL 固定 grant_key=APPROVAL:{requestId}；该值由服务端生成，客户端不能指定。这样既允许永久/临时记录并存，也防止并发首次写入产生重复 MANUAL/审批记录。审批不能改写 MANUAL 的 effects 或整条 expires_at；APPROVAL QUERY_ACCESS 准入记录只填 query_effect=ALLOW，其余 action 为 UNSET。

### 36.2 `datasource_access_policy`

```text
id
datasource_id
subject_type          # USER / ROLE / DEPARTMENT；仅 MANDATORY 行策略可使用 SYSTEM
subject_id            # SYSTEM 时为 NULL，其他类型必填
table_name            # 规范表名，或表级通配特殊值 *
column_name
resource_fingerprint   # 用于识别同名重建或语义漂移
resource_fingerprint_version
granted_snapshot_id
include_future_resources
permission_action     # DISCOVER / QUERY / EXPORT / VIEW_SQL / MANAGE；MASK/UNMASK 作用于 QUERY 结果
access_type           # ALLOW / DENY / MASK / UNMASK / CONSTRAINT；CONSTRAINT 仅 SYSTEM MANDATORY 使用
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
approval_request_id   # UNMASK 必填；其他审批生成的临时策略也必填
status                # ACTIVE / REVOKED；过期依据 valid_until 实时判定
revoked_by
revoked_at
row_version
created_by
created_at
updated_at
```

字段和索引要求：

- `subject_type`、`permission_action`、`access_type`、`row_filter_kind` 使用 Java enum + DTO 校验，数据库仍使用可读字符串；`SYSTEM` 只能用于 MANDATORY 行策略；
- UNMASK 必须是 USER + 精确列 + 有效期 + `approval_request_id`，普通策略 CRUD 不允许直接创建；
- MASK / UNMASK 必须为 QUERY + 精确列；SCOPE 行 DSL 只能附着在 QUERY + 表级 ALLOW 上；CONSTRAINT 必须为 SYSTEM + QUERY + 精确表 + MANDATORY，且不贡献授权。其他组合（尤其 DENY + 行表达式）写入拒绝，不在解析时猜测；
- QUERY / EXPORT / VIEW_SQL / 普通 DISCOVER 的精确表列策略必须绑定当前发布快照，执行时仍按活动快照校验；MANAGE 策略绑定实际管理对象的所属快照/稳定 ID，允许草稿、阻断和废弃对象；`table_name='*'` 只按第 11 节的表级通配规则创建；
- `include_future_resources` 只对 `table_name='*'` 有效；为 false 时 `granted_snapshot_id` 必填，为 true 时仍保存创建基线快照用于审计；
- MANAGE 的 granted_snapshot_id 表示实际管理对象所属快照，可以是草稿；服务从 path/body 资源 ID 解析出真实数据源、所属快照和表列名再匹配，不能拿用户传入的名字替代归属校验；
- 同一表的多个 `column_scope_mode` 按第 15 节先解析成列集合，不直接对枚举值排优先级；
- `valid_until >= valid_from`，时间计划必须在写入时完成结构校验；
- 为 `datasource_id + subject_type + subject_id`、`datasource_id + table_name + column_name`、`approval_request_id` 和有效期扫描建立普通索引；
- 过期判定始终使用 `valid_until` 实时执行；定时任务只负责把过期记录归档/补齐审计，不得在任务运行前继续放行已过期策略；
- 不创建数据库外键。

### 36.3 `access_approval_request` 补充字段

在现有申请表上增加 request_type（QUERY_ACCESS / UNMASK）、generated_grants（类型/ID 关联列表或等价关联结构）、row_version 和 requester_id + idempotency_key 幂等唯一索引。generated_grants 与生成记录的 approval_request_id 必须在同一审批事务内写入，不能先标记 APPROVED 再异步补授权组。重复请求/并发审核使用状态条件更新与乐观锁返回真实冲突，不生成重复记录。

申请人不能提交任意 row_filter_dsl / permissionParameters / grant_key；申请资源由服务端解析，审批确认的表列范围和有效期必须明确。到期时申请状态可更新为 EXPIRED，但授权有效性实时按各记录有效期判断；归档任务只处理 generated_grants 中的记录，不物理删除永久记录或历史审计事实。

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

| 一级业务域 | 工作区 | 路由 | 目标工作区查看码（B0 待逐端点映射） |
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

上表是统一的目标工作区命名输入，不代表端点/Tab/操作矩阵已完成。第 4 节示例和后续开发只能引用这套词汇，不另起 `asset:view` / `permission:manage` 等同义码。在写数据库迁移前必须生成一份“现有权限码 → 目标权限码 → Controller 方法 → 前端路由/Tab/按钮”的完整矩阵并逐项评审。最低查看/操作划分如下；B0 补充遗漏操作时须先登记到矩阵，不能由前后端分别自由命名：

```text
datasource:view / datasource:manage
metadata:collect:view / metadata:collect
metadata:view / metadata:publish:view / metadata:review / metadata:publish
governance:view / governance:check
governance:issue:view / governance:issue:manage
governance:rule:view / governance:rule:manage
governance:field:view / governance:field:manage
glossary:view / glossary:manage / glossary:approve
knowledge:view / knowledge:manage / knowledge:approve / knowledge:publish
prompt:view / prompt:manage / prompt:approve
security:view / security:manage / security:constraint:manage
security:approval:view / security:approve
organization:view / user:manage / role:manage / department:manage
organization:user:view / organization:role:view / organization:department:view / organization:permission:view
audit:view / audit:export
lineage:view / operation-log:view
system:runtime:view
system:ai-config:view / system:ai-config:manage
query:use / query:history:view / query:sql:view / query:export
```

矩阵每一行至少记录：唯一目标码、现有码及迁移方式、HTTP method/path、Controller 方法、关联 Service、所属工作区/Tab/按钮、数据 action、资源 ID→数据源归属解析、是否读取业务值、拒绝测试和真实验收用例。共享下拉框/上下文接口也必须登记，不能因它们被多个工作区使用而保留一个过宽管理权限。

功能权限不会因具有 `:manage` 自动拥有同域所有 `:view`；角色配置必须显式包含工作区和必要 Tab 查看码。`query:sql:view` / `query:export` 与数据 VIEW_SQL / EXPORT 必须同时通过。SYSTEM MANDATORY 的管理端点使用 `security:constraint:manage`，普通 `security:manage` 策略 CRUD 不能修改它。

前端实施规则：

1. `ADMIN_WORKSPACES` 增加工作区查看权限，侧栏只显示至少拥有一个可访问工作区的业务域；
2. 路由 `meta` 声明查看权限，`guards.ts` 正式消费，不能继续只判断“是否具有任一后台权限”；
3. 页面 Tab 声明自己的查看权限，无权 Tab 不渲染，URL 直达时跳到首个有权 Tab 或显示 403；
4. 新建、编辑、删除、审核、发布、导出、查看 SQL 等操作分别检查操作权限；
5. 后端 Controller/Service 使用同一权限码强制校验，前端隐藏按钮不能替代后端鉴权；
6. `*` 只作为功能权限通配符。轨道 B 的目标是让超级管理员也通过明确的数据授权访问业务数据；切换前需为现有管理员回填数据源授权，避免锁死；
7. 无任何可访问后台工作区的用户访问 `/admin/*` 时返回查询端或 403，不能进入一个全空壳后台；
8. 详情路由继承所属工作区查看权限，资源级权限由后端再次校验。

只有完整矩阵、角色回填映射、共享端点策略和拒绝测试逐项评审通过，才能把文档状态从“B0 评审与决策基线”改为“B1～B6 实施基线”。当前正文提供规则和命名输入，不声称该矩阵已经生成或已验收。

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
| 表权限 | 无表级 ALLOW 时可能是 `UNRESTRICTED` | 数据源 QUERY 只作准入；精确/通配表策略给出 ALLOWLIST，通配授权显式绑定快照及未来资源开关 | 是 |
| 时间计划 | 非法 DENY 保持生效，但非法 ALLOW 当前仍可能参与计算 | 非法 DENY 生效、非法 ALLOW 无效，统一 fail-closed | 是 |
| 策略有效期 | 细粒度策略表已有 validFrom / validUntil / timeSchedule，但创建 DTO 和前端不能完整配置 | 写入、读取、校验、展示和解析闭环 | 是 |
| 列权限 | DENY、MASK 已能传给 Python；列 ALLOW 当前被忽略 | 增加 ALL_EXCEPT_DENIED / ALLOWLIST，固定 DENY > MASK > 原值且冲突可解释 | 是 |
| 行权限 | SQL 片段校验后，多来源直接 AND | 结构化 DSL；SCOPE OR、SYSTEM CONSTRAINT 的 MANDATORY AND；绑定值独立传入执行器 | 是 |
| 治理联动 | 已排除活动快照中 BLOCKED / DEPRECATED 表列 | QUERY 保持硬阻断；MANAGE 可以处理未发布/阻断/废弃对象，不被问数门槛锁死 | 保留并补强 |
| RAG | 检索请求没有权限白名单 | 首次检索、相邻扩展、fallback、Schema Linking 全部先过滤；发布前验证完整资源清单与 hash | 是 |
| SQL 安全 | Python 已有表白名单、列拒绝、行过滤 AST 改写 | 保留为执行前第二道硬校验 | 保留并补测 |
| 脱敏 | Python 标记输出字段，Java最终脱敏 | 只允许受控直接投影；MASK 列的条件/分组/表达式推断默认拒绝，Java 持久化前最终脱敏 | 补强 |
| 结果持久化 | Python 返回的结果数据先写入 `query_task.result_data`，读取 VO 时再由 Java 脱敏 | 持久化前完成 Java 最终脱敏，普通查询结果表不保存未授权原值 | 是 |
| Action/结果能力 | 数据源层返回 canExport / canViewSql | QueryPermissionContext 与 ActionDecision 分离；按实际业务资源逐 action 计算，历史/导出时再校验 | 是 |
| 时效 | 已有有效期/时间计划字段 | evaluatedAt / nextEvaluationAt 包含下一次时间变化；RAG、执行、Java 脱敏/持久化/响应前检查 | 是 |
| 导出 | 前端根据 `canExport` 禁用本地导出按钮 | query:export 与实际资源 EXPORT 成对校验；承认已返回浏览器的数据无法靠按钮阻止复制 | 补强 |
| 访问审批 | “申请查看原值”通过后只生成 USER ALLOW，实际上不能解除 MASK | 区分 QUERY_ACCESS 授权组 / UNMASK，增加取消、幂等、双向生成记录关联及精确撤销 | 是 |
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

功能权限只决定“能做某类事”，数据权限继续决定“能对哪些数据源/资产做”。因此后台列表、详情、搜索、统计和批量接口都必须在 Java 查询层应用 DISCOVER / MANAGE 数据范围，并按第 13.1 节区分正式资产发现与待治理对象：

- 列表的 records 与 total 使用同一权限过滤条件，不能先查全量再由前端隐藏；
- 详情、编辑、删除、发布和审批根据资源所属数据源再次校验，防止替换 path/body ID 越权；
- 批量操作逐项校验，采用项目既定的原子或部分成功语义并返回真实结果；
- 工作台聚合数字只统计当前用户可见资源，不能通过数量侧信道泄露无权对象。

### 42.2 智能问数

```text
用户选择数据源并提问
→ Java 校验 query:use
→ Java 校验数据源 readiness
→ Java Permission Resolver 计算请求级 QueryPermissionContext
→ 无 QUERY 或空表白名单：直接 403，不调用 Python/LLM
→ Java 把权限快照发送给 Python
→ Python 校验 nextEvaluationAt，RAG 在任何 LLM 调用前过滤无权/无完整清单 chunk
→ SQL 生成
→ sqlglot 提取全部表、列、JOIN、子查询和 CTE
→ 表白名单、列 DENY 和 MASK 列使用边界二次校验
→ AST 注入行级条件，绑定值通过 permissionParameters 独立传入执行器
→ SQL 执行前再次校验 nextEvaluationAt
→ 只读沙箱执行
→ Python 返回 maskedFields 及经 AST 验证的 usedTables / usedColumns
→ Java 在脱敏、持久化和返回前再次校验 nextEvaluationAt
→ Java 最终脱敏并持久化结果/审计
→ Java 按实际业务表列与 query:sql:view / query:export 计算本任务 canViewSql / canExport
→ 前端按本任务派生能力控制展示与操作，导出 API 调用时 Java 再次校验
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
7. 每条助手消息、结果摘要和图表必须关联 queryTaskId 与实际业务资源血缘。当前权限收紧时，不能只隐藏结构化结果/SQL 却继续显示包含结果值的自由文本；无法安全裁剪时整条助手数据消息隐藏，仅返回不泄露资源和值的状态说明。
8. VIEW_SQL 返回参数化 SQL/权限占位符，不返回 permissionParameters；执行 AST 中的 policyReferencedColumns 与用户业务引用分开保留在受限审计 VO 中。
9. 历史聚合结果无法按新行策略重新逐行裁剪。当前行范围/强制约束与执行时不同，且无法证明当前范围等价或更宽时，隐藏整份历史数据/图表/数据消息并拒绝导出；不能仅按表列名称仍有权限就继续显示旧汇总值。

---

## 43. Permission Resolver 规范

### 43.1 输入

```text
userId
datasourceId
action                 # DISCOVER / QUERY / EXPORT / VIEW_SQL / MANAGE
resourceSet            # 一个或多个 datasource / table / column / metric
requestTime
activeSnapshotId       # QUERY 必填；其他 action 按资源生命周期可空
queryTaskId            # EXPORT / VIEW_SQL 对查询结果决策时必填
```

### 43.2 计算步骤

1. 校验当前用户存在且启用；收集启用的 ROLE、主 DEPARTMENT 及满足显式继承范围的父部门主体。部门禁用或循环时按第 7 节 fail-closed。
2. 校验数据源和 `resourceSet` 真实存在；是否必须健康、已启用或已发布，按第 13.1 节的 action 生命周期矩阵决定，不在通用步骤中一律要求活动快照。
3. 加载这些主体在当前数据源上对应 action 的有效 `datasource_access`。若命中任何 DENY，返回 `DENY_EXPLICIT`；若无 ALLOW，返回 `DENY_NO_GRANT`。
4. 加载对应 action 的细粒度策略，按主体、时间、部门范围、策略状态和资源 fingerprint 过滤；QUERY 还必须单独加载 SYSTEM MANDATORY，不受业务主体筛选遗漏。非法 DENY 按安全策略继续阻断，非法 ALLOW 不参与放行，非法 SYSTEM CONSTRAINT 拒绝查询。
5. QUERY：要求 readiness 通过和活动发布快照；先解析精确表/通配表策略得到 ALLOWLIST，再移除表级 DENY 和治理硬阻断表。没有发布快照返回 `DENY_NO_PUBLISHED_SNAPSHOT`，最终表集合为空返回 `DENY_TABLE_NOT_ALLOWED`。
6. QUERY 列/行范围：先检查第 19 节的行列交叉授权安全条件，再按第 15 节把每个来源解析成列集合，取并集后移除任一列 DENY 和治理硬阻断列，计算 MASK / 有效 UNMASK；按 `SCOPE OR`、`SYSTEM MANDATORY AND` 构造结构化行策略及 rowScopeMode。
7. DISCOVER：只返回当前用户可发现的已发布资产结构及可用状态；草稿和受限管理视图必须另外通过 MANAGE。已发布 BLOCKED / DEPRECATED 状态可由有 DISCOVER 的用户看见，但不进入该用户 QUERY/RAG 范围。
8. MANAGE：按稳定管理资源 ID 和归属数据源校验，允许查看和处理未发布、BLOCKED、DEPRECATED、禁用或不健康对象；该能力绝不派生 QUERY / EXPORT。
9. EXPORT / VIEW_SQL：从 `queryTaskId` 取得经 AST 验证的完整实际业务引用集合和结果血缘（不将内部权限注入列误当成用户授权），校验任务所属用户和数据源，再对每个实际业务资源取执行时与当前权限交集；任一资源 DENY / 无 ALLOW 则整体拒绝。VIEW_SQL 还需 query:sql:view，EXPORT 还需 query:export。
10. 计算 `evaluatedAt`、`nextEvaluationAt`、决策解释和 `decisionVersion`。QUERY 输出可序列化 `QueryPermissionContext`，其他 action 输出资源集合级 `ActionDecision`。

### 43.3 决策原因码

至少统一以下稳定原因码，前端只翻译原因码，不解析后端中文消息：

```text
ALLOW_EXPLICIT
ALLOW_SUPER_ADMIN_COMPAT       # 仅迁移期开关，完成后删除
DENY_USER_DISABLED
DENY_DATASOURCE_DISABLED
DENY_DATASOURCE_UNHEALTHY
DENY_NO_PUBLISHED_SNAPSHOT
DENY_GOVERNANCE_BLOCKED
DENY_EXPLICIT
DENY_NO_GRANT
DENY_TABLE_NOT_ALLOWED
DENY_COLUMN_NOT_ALLOWED
DENY_POLICY_INVALID
DENY_PERMISSION_EXPIRED
DENY_PERMISSION_REVOKED
DENY_CONTEXT_EXPIRED
DENY_ACTION_RESOURCE_MISMATCH
```

### 43.4 幂等与时效

- 在同一权限事实版本且 `evaluatedAt < nextEvaluationAt` 的同一时间窗内，同一用户、数据源、快照和资源输入应得到等价结果；跨过时间计划或有效期边界后不保证结果相同；
- 权限上下文必须冻结到单次查询任务，任务执行中不能一半使用旧权限、一半使用新权限；
- Python 不回调 Java 重新计算权限。上下文带 `evaluatedAt + nextEvaluationAt`，Python 在任何 LLM 调用前、RAG 前和 SQL 执行前检查，Java 在最终脱敏、持久化和返回前检查；超过边界则终止任务，不继续使用旧上下文；
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
- 保留的 DELETE 路径语义改为撤销记录并写审计，不物理删除仍被历史任务/申请引用的授权；
- 权限预览返回数据源、表、列、行、脱敏、来源、原因码和有效期，不只返回三个 boolean；
- 普通用户只能查看和操作自己的申请，审批人权限由后端强制校验；
- 申请 DTO 明确 `requestType = QUERY_ACCESS / UNMASK`；UNMASK 只允许精确列和可覆盖 MASK；
- 审批记录新增 `generated_grants` 或等价类型/ID 关联列表，生成的准入记录和策略均保存 `approval_request_id`；QUERY_ACCESS 授权组必须事务性生成，过期处理按关联 ID 精确撤销/归档，不能按宽泛条件误删其他临时授权；
- 增加申请人取消 PENDING 申请的接口；是否允许撤回已生效授权需另行确认。

### 44.2 Java → Python 查询契约

目标 `userPermissions` 至少包含：

```json
{
  "contractVersion": 2,
  "queryDecision": "ALLOW",
  "decisionVersion": "opaque-version",
  "evaluatedAt": "2026-09-15T01:00:00Z",
  "nextEvaluationAt": "2026-09-15T10:00:00Z",
  "matchedExpiresAt": ["2026-09-15T10:00:00Z"],
  "tableScopeMode": "ALLOWLIST",
  "allowedTables": ["orders"],
  "columnScopeModes": {"orders": "ALLOWLIST"},
  "allowedColumns": {"orders": ["id", "amount", "customer_phone"]},
  "deniedColumns": ["orders.cost_price"],
  "rowPolicies": [
    {
      "tableName": "orders",
      "rowScopeMode": "FILTERED",
      "scopePolicies": [
        {
          "policyId": 101,
          "conditionDsl": {
            "column": "department_id",
            "operator": "EQ",
            "binding": "departmentId"
          },
          "bindings": {
            "departmentId": {"type": "LONG", "value": 101}
          }
        }
      ],
      "mandatoryPolicies": []
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

说明：示例只表示目标结构，不代表当前接口已经支持 `decisionVersion`、`nextEvaluationAt` 和结构化 `rowPolicies`。迁移期可同时接收旧 `rowFilters`，但 Java 每次请求只能标记一种权威格式，Python 不得把两套规则叠加执行。

contractVersion 是协议版本，decisionVersion 是权限事实/时间窗版本，二者不可混用；未知协议版本拒绝，不猜测为旧格式。userId / datasourceId / activeSnapshotId 由 Java 根据认证用户、选定数据源和发布状态放在外层请求中，不能信任浏览器传入的 userId。示例 bindings 是 Java 将存储 DSL 的 valueRef 编译为 binding 引用后的载荷；Python 只能按该编译契约消费，不能再次执行 `$currentUser.*` 字符串替换。

Java 把受控变量解析为上述带类型 `bindings`；Python 只用它构建 AST 占位符和独立 `permissionParameters`。`bindings` / `permissionParameters` 不进入 LLM Prompt、SSE 进度消息和普通日志。

`canExport` / `canViewSql` 不放在这份查询前契约中伪装成全局布尔值。Python 返回经 AST 校验的业务引用并由 Java 保存后，Java 通过 authorizeQueryTaskAction(..., VIEW_SQL / EXPORT, ...) 生成本任务的派生能力；导出请求发生时必须再次计算。包装入口读取可信任务资源，再使用统一 authorizeAction 内核，不能接收浏览器提供的“已用表列”作为事实。

结果契约必须区分 `businessReferencedTables/Columns`（模型原 SQL 的投影、条件、JOIN、分组等全部业务引用）与 `policyReferencedColumns`（权限 AST 注入引用）。ActionDecision 使用完整业务引用和结果列血缘，权限注入列只用于安全执行/受限审计，不能给用户派生列授权，也不能因为内部隔离列不可见而错误拒绝所有业务查询。历史任务无法证明业务引用完整时，VIEW_SQL / EXPORT 默认拒绝，不使用空 lineage 表示无限制。

### 44.3 RAG 检索契约

`RetrieveRequest` 增加请求级权限过滤条件：

```text
tableScopeMode = ALLOWLIST
allowedTables
columnScopeModes
allowedColumns
deniedColumns
decisionVersion
requirePermissionMetadataComplete = true
expectedChunkManifestHashes   # Java 预载的 source_id -> 已发布资源清单 hash；未预载项须有界批量读取
```

过滤规则：

- chunk 的主表和 `related_tables` 必须全部在允许表集合内；
- chunk 的 `related_columns` 命中 DENY，或不在该表的列 ALLOWLIST 时整块排除，除非能够可靠重建并删除无权字段描述；
- `permission_metadata_complete` 必须为 true，且 `permission_resource_manifest_hash` 必须与 MySQL 已发布 chunk 快照一致；缺少标志、清单不完整、hash 不一致或无法证明安全的 chunk 采用 fail-closed，不发送给 LLM；
- Java 批量清单读取接口的准确 path、请求上限和版本条件在 B0 契约中登记；清单 hash 必须来自 Java 的已发布快照，禁止 Python 读取权限表或用向量返回值自证完整性；
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
5. 数据源准入表新增 grant_source / approval_request_id / 撤销状态与乐观锁；迁移旧主体唯一索引，使 MANUAL 永久记录与 APPROVAL 临时记录可独立并存，保留审批幂等索引。
6. 增加 QUERY 执行绑定参数契约、QueryPermissionContext / ActionDecision、知识 chunk 资源清单完整标志与 hash；先部署能够兼容旧请求的新接收方，再允许 Java 发送新契约。

迁移版本号在开发开始时按仓库最新 Flyway 版本顺延，不在本文预占固定编号。`后续开发.md` 曾把 V53 用作未来 `alert_history` 示例，因此实现时必须统一重新编号，避免重复迁移。

### 45.2 Backfill

1. 把当前 `*` 管理员需要保留的业务 QUERY / EXPORT / VIEW_SQL 回填为明确数据源准入和对应表范围；DISCOVER / MANAGE 依 B0 人工确认范围回填，不把功能 `*` 自动变成业务原值权限。
2. 仅对旧 QUERY / EXPORT / VIEW_SQL 按“旧 DENY → 三个 action DENY；旧 ALLOW 的 true → ALLOW、false → UNSET”回填 effects，并对账派生 boolean。新增 DISCOVER / MANAGE 默认 UNSET，按明确映射补齐。
3. 现有 `datasource_access_policy` 的 ALLOW / DENY 统一回填 `permission_action=QUERY`；MASK 按结果策略迁移，不猜测 DISCOVER / EXPORT / MANAGE。
4. 为现有部门授权回填 `department_scope`，默认值必须基于当前真实继承结果生成并人工复核。
5. 为需要保留的 QUERY 表级放开行为生成 `table_name='*'` 或精确表 ALLOW；EXPORT / VIEW_SQL 另按旧 boolean 与旧实际业务表列范围生成各自策略，不复用 QUERY 当授权。所有通配策略默认 `include_future_resources=false`，绑定真实授权基线快照与资源 fingerprint；没有发布快照时只能回填数据源管理准入，不生成“当前全部业务表”策略。
6. 将可安全解析的 `row_filter_expression` 转换为 DSL；同一旧授权来源原来 AND 的条件必须先组成一个 AND 语义组，不能逐条改成 OR。跨来源改为 SCOPE OR 以及“无行条件 ALLOW 允许全表”的目标差异必须单独对账、人工确认；无法转换的标为待处理并阻止 V2 切换。
7. 检查重复、冲突、过期和引用不存在资源的策略，输出对账报告，不静默删除。
8. 旧申请与临时策略能可靠关联时回填 generated_grants / approval_request_id；无法证明归属时标记待人工处置，不按宽条件猜测关联，也不以到期清理误删永久授权。
9. 旧知识 chunk 逐个复核资源绑定和正文，只有验证通过才回填 complete / manifest hash；正则提取结果或历史“已审核”状态本身不足以证明完整。

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
每个 action 的资源集合级决策（包含多表组合）
canExport
canViewSql
evaluatedAt / nextEvaluationAt
QUERY 与 MANAGE 生命周期差异
最终原因码
```

差异必须分类为“预期收紧、预期放开、迁移错误、算法错误”。未解释差异不允许进入 Switch。

### 45.4 Switch

1. 先确认 Python 兼容接收方、参数绑定执行器与 Java 结果后处理已部署，再切 Java Query Resolver / ActionDecision 和 SQL 执行前校验；
2. 在知识资源清单复核通过后切 RAG 权限过滤；未通过的 chunk 继续拒绝，不临时伪造 complete；
3. 再切前端路由/Tab/按钮消费；
4. 最后关闭超级管理员数据权限兼容开关。

每一步都必须可独立回滚，不能一次发布后同时改变数据库、Java、Python和前端全部语义。

“可回滚”不等于可撤掉已生效的安全约束。旧解析器无法表达列 ALLOWLIST、SYSTEM MANDATORY、MASK 使用限制或当前撤销状态时，必须保留独立安全校验覆盖层，或停止受影响查询；不能用切回 V1 恢复越权范围。

### 45.5 Contract

稳定运行并完成真实验收后，才允许清理旧字段、旧 DTO、旧前端分支和兼容开关。删除前保留数据库备份和 Git 可追溯迁移记录。

---

## 46. 分阶段开发顺序

### B0：权限清单与决策冻结

- 生成 Controller API、前端路由、Tab、按钮和现有权限码清单；
- 完成现有权限码到目标权限码的映射；
- 产出第 37 节要求的完整端点/路由/Tab/按钮矩阵，包含共享下拉框接口、query:sql:view / query:export 和 SYSTEM 约束管理端点；
- 冻结 action 生命周期矩阵、数据源 QUERY 准入与表范围分离、通配快照/未来资源规则和多来源列集合合并；
- 冻结 DENY、默认拒绝、超级管理员、部门继承、SCOPE OR / SYSTEM MANDATORY AND、MASK SQL 使用边界和冲突规则；
- 评审 QueryPermissionContext / ActionDecision、typed bindings / permissionParameters、evaluatedAt / nextEvaluationAt 及实际业务引用/权限注入引用的契约；
- 明确资源绑定/知识完整清单验证入口、历史文档复核与不完整 chunk 处置方案；
- 明确普通用户“我的申请”入口与审批人范围；
- 产出数据库回填和回滚方案。

完成条件：完整矩阵、契约 Schema/正反例、授权组关联方案、迁移对账夹具和安全回滚方案逐项评审通过，没有待开发人员自行猜测的核心权限语义。届时再更新本文状态；不能以“正文已写了候选规则”代替这些产出。

### B1：功能权限后端收口

- 新增/补齐查看、管理、审核、发布、导出等权限码；
- Controller 和关键 Service 使用统一权限码；
- 清除同义、过宽或只靠类级 `security:manage` 的端点；
- 保留 `*` 的功能通配语义；
- 增加端点权限参数化测试。

### B2：数据权限 Resolver V2

- 扩展现有两张权限表，不建立第二套永久并行模型；
- 实现明确的 DENY / ALLOW / 默认拒绝算法；
- 实现 resolveQueryContext / authorizeAction 的统一内核；MANAGE 可处理草稿/阻断对象，EXPORT / VIEW_SQL 按实际业务资源逐项校验；
- 实现部门范围、时间条件、nextEvaluationAt、MASK 冲突和结构化行策略；SYSTEM CONSTRAINT 不贡献任何 ALLOW；
- 实现通配授权的未来资源开关、快照发布后的资源一致性检查和规范名称匹配；
- 将权限缓存迁移到 Redis；
- 在写入 `query_task.result_data` 前执行 Java 最终脱敏；如未来确需保存原值，必须使用独立加密存储、单独权限和审计，不得复用普通结果列；
- 完成回填、影子计算和差异报告；
- 扩展权限预览接口。

### B3：Python 权限执行与 RAG 过滤

- 扩展 Pydantic 权限契约；
- 首次检索、相邻扩展、fallback、Schema Linking 共用权限过滤器；
- 发布阶段验证资源清单 complete / hash，检索阶段检查 MySQL / Milvus 清单一致性；
- conversation history / summary、glossary、few-shot、SQL 自校正和图表预览接入统一 Context Firewall；
- SQL Validator 覆盖别名、JOIN、CTE、子查询、星号、未限定列；
- MASK 列只允许受控直接投影，条件/排序/分组/窗口/函数/CASE 推断默认拒绝；可信行权限 AST 与业务 SQL 引用分开记录；
- 行策略使用 AST 占位符构建，permissionParameters 独立传入执行器；自校正重试必须重新注入且不向 LLM 暴露绑定值；
- RAG 与 SQL 执行前检查 nextEvaluationAt；Python 返回脱敏字段与实际资源引用，Java 保持最终脱敏和响应前时效校验。

### B4：前端正式消费权限

- `ADMIN_WORKSPACES`、路由 meta、侧栏、Tab 和按钮接入权限；
- 所有无权直达路由有确定的 403/跳转行为；
- query:sql:view / query:export 与任务级 VIEW_SQL / EXPORT 派生能力真实联动，不从数据源级 boolean 猜测多表结果权限；
- `canExport` 只约束产品提供的导出功能；数据已经返回浏览器后无法从技术上禁止复制，若业务要求严格防外泄，需要另行设计服务端导出、水印和 DLP，不在本阶段伪装成已解决；
- 授权管理页展示最终决策解释、冲突和有效期；
- 历史会话和结果恢复按执行时权限与当前权限交集展示；
- 普通用户申请入口按产品确认结果落地。

### B5：审批、审计和失效闭环

- 审批幂等、乐观锁、generated_grants / approval_request_id 双向关联、取消和精确到期归档完整；QUERY_ACCESS 授权组事务性创建，不覆盖 MANUAL 永久记录；
- 权限、组织、治理和快照事件正确失效 Redis；
- nextEvaluationAt 到达时主动取消任务，Java 在脱敏、持久化和响应前再次拒绝过期上下文；
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
- 新建/禁用/不健康数据源、草稿快照和 BLOCKED / DEPRECATED 对象仍可由有范围的 MANAGE 用户治理，但不能因此 QUERY 或读取业务样例值；
- 多表查询任一实际业务资源缺少 VIEW_SQL / EXPORT 时整体拒绝；功能 query:sql:view / query:export 与数据 action 均通过才生成任务能力；
- USER ALLOW 不能覆盖 ROLE / DEPARTMENT DENY；
- 父部门 CURRENT 不向子部门继承；
- CURRENT_AND_CHILDREN 正确继承且组织循环 fail-closed；
- 过期、未来生效、工作日、跨午夜时间计划；
- 永久 QUERY + 限时 UNMASK + 周期 EXPORT 的 nextEvaluationAt 正确；边界前开始、边界后返回的任务不能保存/返回原值；
- 时间计划非法时 DENY 有效、ALLOW 无效；
- 数据源 QUERY ALLOW 而无表策略时拒绝；通配 * 必须为表级、绑定真实快照，false 不授予未来表列、true 才显式继承，历史快照不可读时拒绝；
- ALLOW * 减去表 DENY；同名删除/重建、结构相同但连续性无法证明时不能自动重新授权；
- 列 ALL_EXCEPT_DENIED 与 ALLOWLIST 语义明确，列 ALLOW 不会隐式授予表权限；
- 不同来源混合列模式先解析成集合再 OR，任一 DENY 在并集后生效；
- 行列范围同时不同且无法证明安全时拒绝配置/解析，不生成授权笛卡尔积；ALL_ROWS / FILTERED 与空 scopePolicies 语义明确；
- 表 DENY、列 DENY、MASK 的固定优先级；
- MANDATORY_MASK 不可解除，OVERRIDABLE_MASK 只有有效审批 UNMASK 才能解除；
- 多 SCOPE 行范围 OR，多 SYSTEM MANDATORY 条件 AND；普通主体不能写 MANDATORY，SYSTEM CONSTRAINT 不能产生表/列 ALLOW；
- 治理 BLOCKED / DEPRECATED 不可通过 QUERY 授权和审批绕过，但 MANAGE 保留修复入口；
- 审批重复提交、并发审批、取消；QUERY_ACCESS 授权组完整关联，到期只归档/撤销关联记录，不影响永久授权；UNMASK 不顺带授予 QUERY；
- ROLE / DEPARTMENT 变更使所有受影响用户 Redis 缓存失效；
- Redis 故障时数据库回源，数据库故障时拒绝而不是放行；
- 缓存 TTL 不跨 nextEvaluationAt；ActionDecision key 包含 action 和完整资源集合，不跨任务串用；
- 决策解释返回正确命中来源和原因码；
- Controller 每个端点的权限码、Tab 查看码与 `*` 功能通配，SYSTEM 约束只有专用端点可以修改；
- 后台列表、total、详情、统计和批量操作都按 DISCOVER / MANAGE 数据范围过滤，替换资源 ID 不能越权。

### 47.2 Python 单元测试

至少覆盖：

- 空 ALLOWLIST 拒绝全部表；
- JOIN、CTE、子查询中任一无权表导致拒绝；
- 表别名、未限定列和 `SELECT *` 不绕过列权限；
- 列 ALLOWLIST 为空时拒绝查询；`SELECT *` 继续按现有安全规则拒绝，不能借星号绕过列白名单；
- RAG 主 chunk、相邻 chunk、fallback 使用同一权限过滤；
- 正文有额外表列、元数据漏标、complete 缺失/false、manifest hash 与 MySQL 不一致时不进入 LLM；自由文本不能通过“正则未命中”自动标为 complete；
- 多表 Join Path 有一张无权表时整块排除；
- denied column 不出现在 LLM Schema 上下文；
- few-shot 中含额外无权表/列时整条排除；用户级候选不会被其他用户直接召回，共享示例已审核并移除敏感字面量；glossary 关联资源按权限过滤；
- conversation history / summary 在撤权和切换快照后不进入新 Prompt；
- 图表 LLM 只收到已脱敏预览，不收到 MASK 字段原值；
- MASK 列用于 WHERE / JOIN ON / HAVING / GROUP BY / ORDER BY / DISTINCT / 窗口 / CASE / 函数 / 算术时拒绝；COUNT(*) + 敏感列谓词也拒绝，不能仅测 SELECT 输出脱敏；
- 行策略 AST 在已有 WHERE、JOIN、子查询下语义正确；
- 受控变量由 Java 解析，类型/NULL/空 IN/列表长度校验；引号、Unicode、特殊字符只作为绑定值，不改变 SQL AST；
- departmentIds 只包含当前启用主部门；缺失变量或非法 SYSTEM 条件拒绝查询，不跳过强制约束；
- permissionParameters 到执行器完整保留，IN 值独立绑定；SQL 自校正重新验证/注入，不能改写策略或丢失参数；
- LEFT / RIGHT JOIN 注入行条件后不被意外改写成 INNER JOIN，同表多别名分别受限；
- MASK 能追踪直接投影别名、派生表和 CTE，无法证明安全的复杂表达式拒绝；可信权限条件单独标记，不变成用户列授权；
- SQL 自校正重试不能丢失权限上下文；
- 权限上下文缺失、超过 nextEvaluationAt 或格式非法时 fail-closed；RAG 前与执行前分别覆盖到期竞态。

### 47.3 前端单元测试

至少覆盖：

- 业务域和工作区按权限过滤；
- 路由 meta 拒绝无权直达；
- Tab 默认值不会落到无权 Tab；
- 写按钮、审批、发布、导出、SQL 展示分别按权限控制；
- 多表任务级 canExport / canViewSql 变为 false 时页面不沿用数据源旧 boolean；查看码与操作码不互相推导；
- `*` 只通配功能权限展示，不在浏览器构造数据权限；
- 权限刷新后导航立即更新；
- 403、权限到期和任务中途撤权有明确反馈。

### 47.4 集成与安全测试

- Java → Python 权限契约字段一致；
- QueryPermissionContext / ActionDecision 分离；typed bindings 经 AST 占位符到真实 MySQL 参数执行闭环，不在 Prompt/SSE/普通日志中暴露值；
- 数据源 readiness、Permission Resolver、RAG、SQL、脱敏和审计全链路；
- 当前权限收紧后，历史消息、任务结果、SQL、导出和长期摘要按“执行时与查看时权限交集”处理；
- 绕过前端直接调用 API 仍返回 403；
- 请求参数篡改 userId / datasourceId / tableName 不越权；
- 并发撤权后新任务立即拒绝；紧急撤权按 Java 主动取消规则停止匹配的进行中任务；
- nextEvaluationAt 前启动、到期后执行/响应/导出均拒绝，Java 最终处理不能绕过 Python 的时效检查；
- 旧接收方/旧 Resolver 回滚无法表达新约束时停止受影响查询，不恢复原有 UNRESTRICTED 或失效授权；
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
15. 有 MANAGE 范围的治理人员可处理未发布、BLOCKED、DEPRECATED、禁用/不健康对象；无 QUERY 时不能利用管理页面读取业务原值/采样值。
16. 数据源 QUERY 准入但无表策略时问数拒绝；通配 false 发布新表/列后不自动放行，true 只在显式确认后继承；历史快照不可读或同名重建不恢复原授权。
17. 多表 SQL 任一业务表列无 VIEW_SQL / EXPORT，任务能力为 false，直调历史/导出 API 也被拒绝；不同 action 不共享授权结论。
18. MASK 直接投影安全脱敏；WHERE / GROUP BY / ORDER BY / CASE / 函数推断及 COUNT(*) + 敏感谓词均被拒绝，SSE 和 chartConfig 不含漏脱敏原值。
19. 永久 QUERY、限时 UNMASK、周期 EXPORT 的 nextEvaluationAt 覆盖跨午夜及响应竞态；到期后即使 Python 已执行，Java 也不保存/返回旧上下文授权的原值。
20. SYSTEM MANDATORY 不贡献 ALLOW；普通策略 CRUD 无权修改；typed bindings 到真实 MySQL 参数执行完整且不进 LLM/SSE/普通日志。
21. chunk 正文有额外资源、complete 缺失/false 或 MySQL/Milvus manifest hash 不一致时不进模型，邻接和 fallback 不能绕过。
22. QUERY_ACCESS 生成完整限时授权组，重复审批只生成一次，到期只处理关联记录；用户原有永久授权、其他申请和受限审计事实仍保留。

---

## 49. 回滚与故障策略

1. 所有迁移先增后删；旧列至少保留到 B6 验收完成。
2. Resolver V2、RAG 过滤和前端消费使用独立开关，出现问题按相反顺序回滚。
3. 回滚不能恢复已过期、已撤销或被治理阻断的业务权限；旧解析器无法表达列 ALLOWLIST、SYSTEM MANDATORY、MASK 使用边界、授权组或时效边界时，保留独立安全校验覆盖层或停止受影响查询，不直接恢复 V1 放行语义。
4. Redis 故障回源数据库；数据库或权限计算不可用时停止查询，不能切到 UNRESTRICTED。
5. Python RAG 权限过滤异常时停止生成 SQL；不得用未过滤 fallback 保持“可用性”。
6. Java 最终脱敏失败时不返回结果数据，并保留可审计的失败原因。
7. Flyway 迁移失败时修复前向迁移，不手工篡改历史迁移 checksum。
8. 回滚、补偿和人工处置不得删除仍有效的权限和审批记录。
9. 到达 nextEvaluationAt 后终止当前任务；不通过改成 NULL、延长缓存 TTL 或跳过 Java 响应前检查维持可用性。
10. 资源清单未验证、hash 不一致或历史业务 lineage 不完整时拒绝对应 RAG / VIEW_SQL / EXPORT，不把缺失信息回填为“允许全部”。

---

## 50. Definition of Done

只有同时满足以下条件，轨道 B 才能标记完成：

- 本文所有 [目标] 已实现，未实现项已明确移出轨道 B 并获得确认；
- 功能权限矩阵覆盖全部正式后台路由、Tab、按钮和 Controller 端点；
- B0 的 action 生命周期/资源映射与完整权限码矩阵已评审；MANAGE 可治理未发布/阻断对象，但不会派生业务原值权限；
- 普通用户数据权限默认拒绝，DENY、部门继承、行策略和 MASK 语义与本文一致；
- 数据源 QUERY 准入与表范围分离；通配快照/未来资源、混合列模式、同名重建和多资源 action 决策均真实消费；
- Java、Python、前端使用同一份可版本化权限契约；
- QueryPermissionContext / ActionDecision、typed bindings / permissionParameters、业务/权限注入血缘和任务级能力契约均通过正反例；
- RAG 的所有入口在 LLM 调用前完成权限过滤，complete / manifest hash 的发布验证和检索一致性真实通过；
- SQL AST 二次校验、MASK 推断限制、SYSTEM MANDATORY、绑定参数执行和 Java 整份响应最终脱敏均通过安全测试；
- evaluatedAt / nextEvaluationAt 覆盖 RAG、执行、脱敏、持久化、响应与导出时效；到期竞态不会返回未授权原值；
- QUERY_ACCESS 授权组 / UNMASK 双向关联、幂等、取消、精确撤销与审计保留闭环；
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
- 任意单元格级授权和未经评审的敏感列聚合；无法安全展平的行列交叉授权首版拒绝，不声称已支持；
- 让 Python 或前端持久化独立权限事实；
- 只在 Prompt 中描述权限而不做 AST 强制校验；
- 与权限无关的 RAG 全量重建、效果评测和告警执行功能；
- 为了“模型完整”而同时保留两套长期运行的数据权限表。

这些能力如果以后进入范围，应先更新本文和执行队列，再开始实现。
