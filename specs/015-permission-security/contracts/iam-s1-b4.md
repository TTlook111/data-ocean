# IAM-SIMPLE-1 B4 页面与业务接入合同

状态：B4 代码已实现并完成一轮 P1 复核修复，已提交并推送（`8a9c5a1`，94 文件 / +9644 行）。2026-09-20 复审新发现前端 6 个 P1 与后端 4 个 P2，已修复全部 6 个前端 P1 与后端 B1 / B2 两项（见文末“2026-09-20 复审发现”），修复尚未提交、未推送、未做浏览器验收。范围结论见“B4 复核后的范围结论”：这是“权限与组织域 + 独立安全问数入口”的 B4 初版，**不是完整 B4，不能据此进入 B5 验收**。V57 只新增 S1 前向表，未执行真实数据库升级；未启动服务、未做浏览器验收、未执行 bootstrap、未正式切换、未清理旧权限。

## 目标与边界

B4 把 IAM-SIMPLE-1 从“服务端已实现”推进到“页面与业务成对接入”：

- 新功能只读取 S1 新表、`IamS1AuthorizationResolver`、`IamS1DataAuthorizationResolver`、revision 与 S1 缓存事实；
- 不读取、不映射、不回填旧角色权限与旧数据授权，不使用旧 JWT authority 或旧权限缓存给新体系授权；
- 不做双读双写，S1 拒绝后不回退旧权限；
- Java 是权限生命周期、持久化、当前权限复查与最终脱敏的安全边界；
- Python 只消费请求级快照，执行 Context Firewall、RAG 过滤、SQL AST 校验、记录条件注入与安全查询；
- 前端不是安全边界：能力摘要只用于界面可见性，直接调用 API 一律由 Java 校验。

## 新增管理端接口（`/api/iam-s1/**`）

| 能力 | 方法与路径 | 服务端强制校验 |
|---|---|---|
| 能力摘要 | `GET /api/iam-s1/capabilities` | 仅返回本人 S1 能力 |
| 角色模板 | `GET /api/iam-s1/templates/role-templates` | 只引用固定目录功能码 |
| 授权模板 | `GET /api/iam-s1/templates/grant-templates` | 静态中文模板 |
| 可选数据源 | `GET /api/iam-s1/datasources` | 负责范围（系统管理员为全部启用源） |
| 选择对象 | `GET /api/iam-s1/subjects?scope=&datasourceId=&subjectType=&keyword=` | `scope` **必填枚举**，缺失或未知值一律拒绝（无旧调用需要兼容）。`GRANT` → `security:permission:view` + **请求中的 datasourceId** 负责源（同一绑定）；`EFFECTIVE` → `security:effective:view` + 该数据源负责源；`ORGANIZATION` → `organization:user:view`（全局，不接受 `datasourceId`，只读不要求 `manage`）。只返回必要名称 |
| 功能目录 | `GET /api/iam-s1/functions` | `organization:permission:view`，只读 |
| 角色 | `GET/POST /api/iam-s1/roles`、`GET/PUT/DELETE /api/iam-s1/roles/{id}` | `organization:role:view` / `organization:role:manage` + 敏感功能仅系统管理员 |
| 角色成员 | `GET /api/iam-s1/roles/{id}/members` | `organization:role:view` |
| 用户角色 | `GET/POST /api/iam-s1/users/{userId}/roles`、`DELETE .../{roleId}`、`PATCH .../{roleId}/disable` | `IamS1UserRoleService`（含本人保护、受保护角色唯一性、最后管理员保护） |
| 后台负责源 | `GET/PUT /api/iam-s1/user-roles/{id}/datasources`、`DELETE .../{datasourceId}` | 仅系统管理员，且不能修改本人负责源 |
| 资源选项 | `GET /api/iam-s1/datasources/{id}/snapshots`、`.../snapshots/{sid}/tables`、`.../tables/{table}/columns` | `security:permission:view` + 负责源；只读已发布快照 |
| 数据授权 | `GET/POST /api/iam-s1/data-grants`、`POST /batch`、`GET/PUT/DELETE /{id}` | `security:permission:manage` + 负责源；三项必选与明确字段由 `IamS1DataGrantService` 强制 |
| 字段保护 | `GET/POST /api/iam-s1/field-protections`、`DELETE /{id}` | `security:mask:view` / `security:mask:manage` + 负责源 |
| 实际权限预览 | `POST /api/iam-s1/effective-permissions/preview` | 本人预览需 `query:use`；查看他人需 `security:effective:view` + 负责源；复用统一 Resolver |
| 我的申请 | `POST /api/iam-s1/access-requests`、`GET /mine`、`POST /{id}/withdraw` | `query:use`，只能看/撤回本人申请 |
| 审批队列 | `GET /api/iam-s1/access-requests/queue?status=&page=&size=` | `security:approval:view` + 负责源（**同一角色绑定**）；`status` 取 `PENDING` / `HANDLED` / 留空，跨负责源单条查询 + 数据库分页，返回 `Page<VO>` |
| 审批 | `POST /api/iam-s1/access-requests/{id}/review` | `security:approval:review` + 负责源；不能审批本人；批准范围必须是申请子集；批准到期时间必填且有上限 |

## 用户侧资源接口（`/api/iam-s1/query-resources/**`）

与后台配置资源接口（上表“资源选项”，要求 `security:permission:view` + 负责源）**严格分离**——普通问数用户可能有 `query:use` 和数据授权，却没有后台负责源，不能复用管理员负责源逻辑：

| 能力 | 方法与路径 | 服务端强制校验 |
|---|---|---|
| 可选数据源 | `GET /api/iam-s1/query-resources/datasources?scope=QUERY\|APPLY` | `query:use`；QUERY 只返回“主体命中且当前有效 ALLOW 授权”的数据源（判定复用 `IamS1DataAuthorizationResolver.hasEffectiveAllowGrant`）；APPLY 返回存在已发布快照的启用数据源 |
| 已发布快照 | `GET .../datasources/{id}/snapshots` | 同上；QUERY 要求该源上有有效授权，APPLY 只要求存在已发布快照 |
| 表 | `GET .../snapshots/{sid}/tables` | 同上；不可查询/不可申请的表标记为不可选 |
| 字段 | `GET .../tables/{table}/columns` | 同上；隐藏字段与治理状态不允许的字段标记为不可选 |

两个 scope 都要求“使用问数”；返回内容一律不含连接信息、密码或业务记录。管理端授权面板继续使用“资源选项”接口（`security:permission:view` + 负责源）。

## 问数资源声明的 usage 约定

Java `IamS1QueryServiceImpl.requireExplicitUsages` 强制要求每个字段声明非空 usage。默认集合由 `IamS1UsageDefaults.DEFAULT_QUERY_USAGES` 统一定义：`PROJECTION/FILTER/JOIN`（与 B3 在 AST 层强制校验的三个位置一致）。前端 `IAM_S1_DEFAULT_QUERY_USAGES` 必须与之对齐，`iamS1Ask` 在提交前为每个字段补齐；排序、分组、聚合与子查询位置由页面显式勾选后以 `columnUsages` 声明。**两侧默认集合各有测试钉住，改动必须同步。**

## 能力摘要（前端可见性的唯一来源）

`IamS1CapabilitySnapshotVO` 字段：`protocolVersion/userId/systemAdmin/globalFunctions/datasourceCapabilities/queryUse/viewSql/export/permissionRevision`。

- `globalFunctions`：启用角色上被授予的功能码（与 `hasGlobalFunction` 同源）。
- `datasourceCapabilities[].functionCodes`：**在同一用户角色绑定上**同时成立“功能 + 负责源”的功能码集合。
  前端不得把不同角色的功能与负责源相乘，后端 `resolveAdminAction` 同样拒绝交叉相乘。
- 前端只用它显示/隐藏路由、工作区、Tab 与按钮；无能力时给出明确中文提示，不静默展示空数据、不回退旧权限。

## 访问申请与审批（V57）

`V57__iam_s1_access_request.sql` 新增两张 S1 表（未执行真实升级）：

- `iam_s1_access_request`：`requester_id/datasource_id/metadata_snapshot_id/table_name/requested_columns_json/row_scope/requested_valid_until/purpose/status/revision_no`。
  只保存资源标识与申请范围，不保存业务原值与记录条件原值；B4 第一期 `row_scope` 仅 `ALL`。
- `iam_s1_access_approval`：`request_id`（唯一键，天然防止重复审批）、`reviewer_id/decision/approved_columns_json/approved_valid_from/approved_valid_until/generated_grant_id/reason`。

审批通过时：

1. 锁定申请行（`selectForUpdate`）并要求 `status = PENDING`；
2. 校验审批人与申请人不是同一人、申请人与数据源仍启用、快照仍发布、表字段仍可授权；
3. 校验批准字段是申请字段的子集；批准到期时间**必填**、必须晚于当前时间，且不超过“申请时长与系统上限 30 天中的较小值”——审批只能生成有期限的临时授权；
4. 以“申请人 + 批准字段 + 默认 usage”调用统一 Resolver：只有其明确给出 `DATASOURCE_DENY` / `TABLE_DENY` / `FIELD_DENY` 时才拒绝（主体匹配、角色/部门继承、有效期与字段级判定全部由统一 Resolver 完成）；“缺少完整覆盖的允许授权”正是审批要补足的缺口，不阻断；事实读取失败时 fail-closed；
5. 拒绝隐藏字段；
6. 写入审批事实（`request_id` 唯一）→ 生成 `iam_s1_data_grant`（`grant_source = APPROVAL`、`source_reference_id = requestId`、主体为申请人、表级 ALLOW、有期限）→ 更新申请状态；三步同一事务。

审批生成授权使用独立判定入口 `IamS1DataGrantService#createApprovalGrant`：以 `security:approval:review` + 负责源为准，而不是 `security:permission:manage`，因此审批人无需本人拥有该业务查询数据。

## 前端接入

| 路由 | 工作区 | 内容 |
|---|---|---|
| `/admin/access/iam` | 授权配置（IAM-SIMPLE-1） | Tab：数据授权 / 字段保护 / 实际权限 |
| `/admin/access/iam-approvals` | 访问申请与审批 | Tab：我的申请 / 待我审批 / 已处理 |
| `/admin/access/iam-organization` | 角色与负责源 | Tab：角色（中文功能矩阵 + 模板）/ 用户角色与负责源 / 功能目录 |
| `/query/iam-s1` | IAM-SIMPLE-1 安全问数 | 资源声明 → 提交 → 任务读取 / SQL / 导出 / 反馈 / SSE |

- 旧入口（`/admin/access`、`/admin/access/approvals`、`/admin/access/organization`、`/query`）在 B5 正式切换前继续保留运行，路由未删除；
- S1 问数入口只调用 `/api/iam-s1/query/**`，SSE 使用 `fetch + ReadableStream` 以携带 `Authorization` 头，订阅失败只用 S1 轮询兜底，不回退 `/api/query`；
- 查询页面要求先声明本次使用的表和字段（资源声明），服务端据此生成权限快照，不存在“先查询、后补权限”的顺序。

## B4 复核后的范围结论（必须按此口径表述）

本阶段实际交付的是 **“权限与组织域 + 独立安全问数入口”的 B4 初版**，不是完整 B4：

- 已接入 S1：权限与组织域（角色、用户角色与负责源、数据授权、字段保护、实际权限预览、访问申请与审批）与独立问数入口 `/query/iam-s1`；
- **未接入 S1**：工作台、数据接入、数据资产、数据治理、语义中心、运营与平台六个后台业务域的后端接口仍走切换前旧链路；B4 只完成 S1 能力摘要与界面可见性接入。
- 因此以下原始 B4 完成标准**尚未达成**：七个业务域的路由、工作区、Tab、按钮与直接 API 全部使用新语义。这些域的服务端 S1 化按 B0 清单在 B5/B6 完成。

其他未完成项：

- 未执行 V57（以及 V55/V56）真实数据库升级；
- 未启动 Java/Python/前端服务，未做任何浏览器验收；
- 未执行 bootstrap，未正式切换，未删除旧权限。

## 复核发现的 4 个 P1 及修复

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | 新问数入口必然缺少 `columnUsages`：前端只发 `tableName/referencedColumns`，而 Java `requireExplicitUsages` 强制要求每个字段都有 usage，提交会被“字段 usage 缺失”直接拒绝 | 新增 `IamS1UsageDefaults`（默认 `PROJECTION/FILTER/JOIN`，与 AST 强制校验的三个位置一致）；前端 `IAM_S1_DEFAULT_QUERY_USAGES` 与之对齐，`iamS1Ask` 对每个字段补齐 usage，页面可显式放开排序/分组/聚合/子查询；后端错误信息改为指出具体表与字段。两侧默认集合各有一个测试钉住 |
| P1-2 | 普通问数用户无法选择查询或申请资源：`/api/iam-s1/datasources` 返回后台“负责源”，资源接口还要求 `security:permission:view` + 负责源，普通用户没有任何可选项 | 新增用户侧资源接口 `/api/iam-s1/query-resources/**`：`scope=QUERY` 的可见性来自 `IamS1DataAuthorizationResolver.hasEffectiveAllowGrant`（与真实查询同一套主体匹配与有效期判定），`scope=APPLY` 只要求“使用问数” + 已发布快照。后台配置资源接口保持“`security:permission:view` + 负责源”不变。问数页、申请页、实际权限页（本人）改用新接口 |
| P1-3 | 审批可生成永久授权：`requestedValidUntil` 与 `approvedValidUntil` 均可为空，直接 API 可绕过前端默认值 | 审批通过时 `approvedValidUntil` **必填**且必须晚于当前时间，上限为“申请时长”与系统上限 30 天中的较小值；前端改为弹窗显式确认到期时间。后端不依赖前端默认值 |
| P1-4 | 任意主体的 DENY 都会阻断所有人的审批（未判断主体、角色/部门继承、有效期、字段覆盖） | 删除自建的简化遍历，改为复用统一 Resolver：以申请人 + 批准字段（默认 usage）调用 `resolve`，只有统一 Resolver 明确给出 `DATASOURCE_DENY` / `TABLE_DENY` / `FIELD_DENY` 时才拒绝；`NO_ALLOW_COVERING_FIELDS` 等“缺少允许授权”正是审批要补足的缺口，不阻断。事实读取失败时 fail-closed |

同时修正的同类缺陷：前端把日期以 `new Date(...).toISOString()` 发送给 `LocalDateTime` 字段（带 `Z` 后缀无法解析），数据授权、申请和审批三处统一改为不带时区后缀的本地时间。

## 已知待办（不属本轮修复）

- 表/字段选项的“可选”装配逻辑在管理端授权面板与用户侧资源服务中各有一份实现，两处口径必须保持一致，计划在 B5 前合并为共用装配器。

## 2026-09-20 复审发现（已修复，未提交）

### 前端 P1

| 编号 | 问题 | 位置 | 修复 |
|---|---|---|---|
| F1 | 路由守卫把 `query:use` 当成后台能力，只有“普通问数用户”角色的用户可被放行进 `/admin/**`；`iamS1.reset()` 生产零调用点，同一标签页切换账号沿用上一用户的能力快照 | `router/guards.ts:78-85`、`stores/iamS1.ts:26-29` | `hasAnyAdminCapability` 排除 `query:use` / `query:sql:view` / `query:export`；`auth.login` / `auth.logout` 调用 `iamS1.reset()` |
| F2 | `currentTaskId` 在 `finally` 中被清空，查询完成后“查看 SQL / 导出 CSV / 反馈”全部静默失效 | `views/query/IamS1QueryView.vue:245,260,277,288,303` | 改为在 `ask()` 开始时清空，完成后保留 |
| F3 | 审批弹窗预填时间带 `T` 与秒，与 `inputPattern`（要求空格、无秒）冲突，不改日期直接确认必被拒 | `views/admin/access/IamS1ApprovalView.vue:161-174,187` | `formatLocal` 改为空格无秒，提交时再转 `T…:00` |
| F4 | 「实际权限」Tab 的放行条件与面板数据依赖自相矛盾，普通问数用户打开后快照/表/字段永远为空 | `IamS1AccessWorkspaceView.vue:24`、`IamS1EffectivePermissionPanel.vue:139-148` | `loadUsers()` 自行降级；本人预览改走 `query-resources/**?scope=QUERY` |
| F5 | DECIMAL 列 + 整数值被前端推断为 `INTEGER`，被后端拒绝且界面无修正入口 | `IamS1DataGrantPanel.vue:186-198` | 值类型只由列的元数据类型决定，与后端 `ensureTypeCompatible` 分支对应 |
| F6 | 能力判定全部用按任意绑定的 `hasGlobal`；store 中同绑定实现 `canOnDatasource` 零调用点 | `stores/iamS1.ts:41-46` 及各视图 | 新增 `datasourcesWithFunction()`，两个面板的数据源下拉按同绑定过滤；审批按钮逐条 `canOnDatasource` |

### 后端 P2

| 编号 | 问题 | 位置 | 修复 |
|---|---|---|---|
| B1 | `safeSummary()` 对 `purpose` / `reason` 做关键词黑名单，命中 `database` / `token` / `secret` 等词抛异常并回滚申请或审批（审批路径连带回滚已生成授权）；且统一按 2000 判断而 `reason` 列只有 500 | `IamS1AuditEventServiceImpl.java:56-70`，调用点 `IamS1AccessRequestServiceImpl.java:143,261` | 改为就地屏蔽 + 按列截断（500 / 2000 / 100），不再抛异常 |
| B2 | `/api/iam-s1/subjects` 与审批队列读路径的“功能 + 负责源”可分别由不同角色绑定满足，与同一绑定规则不一致 | `IamS1CapabilityServiceImpl.java:144`、`IamS1AccessRequestServiceImpl.java:189` | 队列改用 `responsibleDatasourcesWithFunction()`（SQL 要求 `rd.user_role_id = ur.id`）；`/subjects` 改为按上文 `scope` 三用途分派校验——`GRANT` / `EFFECTIVE` 必须校验**请求中的 datasourceId**（不能只要求“至少负责一个源”，否则负责 A 源的人能按 B 源的用途加载主体），`ORGANIZATION` 走全局只读码 |
| B3 | 审批队列 `QUEUE_LIMIT_PER_DATASOURCE = 100` 且无重复申请限制，可被刷量挤占 | `IamS1AccessRequestServiceImpl.java:64` | 队列改为按状态分组真分页（`status` + `page` + `size`，跨负责源单条查询走 MyBatis-Plus 分页插件），待审批不再被同源的已处理记录挤出，也不再有无入口可处理的截断；删除了不再使用的 `selectByDatasource` / `selectPendingByDatasource`。批量写入另加服务端 100 条上限（`MAX_BATCH_GRANTS`）。**“无重复申请限制”仍未处理** |
| B4 | `review` 的权限判定排在存在性与状态判定之后，可借错误消息差异枚举 requestId | `IamS1AccessRequestServiceImpl.java:207-214` | 未修复 |

未发现 P0 级越权：10 个 Controller 均有服务端强制校验，审批的并发 / 重复 / 自审批 / 时限 / 字段子集处理正确，事务边界正确，全包无 SQL 拼接。


## B4-A 统一鉴权接入框架（2026-09-20，未提交）

新增三个**方法级**注解，语义严格区分：

| 注解 | 语义 | 服务端判定 |
|---|---|---|
| `@IamS1Global(code)` | 全局功能 | 校验功能码为 B0 冻结的「全」语义 + `requireGlobalFunction` |
| `@IamS1Resource(function, resourceType, resourceIds)` | 资源范围功能 | 逐个解析资源真实归属（解析器重查，不采信请求自带 datasourceId）+ `requireDatasourceFunction`；多资源任意一个无权即整体拒绝 |
| `@IamS1ScopedList(code)` | 源范围功能的**功能级**准入 | 只校验功能码为「源」语义 + `requireGlobalFunction`；用于列表/搜索/统计入口与无现成资源 ID 的创建/探测入口。**不解析资源、不裁剪数据**——可见范围由 Service 下推 SQL |

切面 `IamS1AuthorizationAspect`（`@Order(HIGHEST_PRECEDENCE + 10)`，在事务切面之前）复用 `IamS1AdminGuard`，不复制授权算法；`IamS1FunctionCatalog.scopeOf()` 校验注解语义与 B0 一致，源范围功能错标为全局会直接拒绝执行。`glossary:*` 三个「源/全」混合码标为 `MIXED`，注解框架拒绝使用。

资源解析器固定注册表：DATASOURCE / SNAPSHOT / METADATA_ENTITY / METADATA_COLUMN；一种类型有且只有一个解析器；未注册类型、资源不存在、归属断链、参数为空、表达式失败一律 fail-closed。受限 SpEL 用 `SimpleEvaluationContext.forReadOnlyDataBinding()`，禁止 Bean、类型引用、构造对象与任意方法调用。

**已迁移 41 个端点**：Dashboard 1、DatasourceAdmin 11、MetadataCatalog 12、MetadataCollection 8、SnapshotVersion 9。列表范围、批量校验、双侧快照校验、血缘裁剪、事务锁与业务规则保留在 Service。

覆盖扫描 `IamS1EndpointCoverageTest` 用 `RequestMappingHandlerMapping` 枚举真实 HandlerMethod。例外清单：已迁移范围精确到 Handler 方法，未迁移部分按 Controller + 计划批次登记，不使用路径前缀匹配。

**未完成**：完整 `confirm()` 并发事务集成测试；权限与组织域 22 个 `IamS1*` 端点仍是显式 Guard。

## B4 批次 4：数据治理域接入（2026-09-20，未提交；含复审后修复）

`MetadataGovernanceController` 12 个端点迁入方法级注解，例外清单 174 → 162。

| 端点 | 功能码 | 资源语义 |
|---|---|---|
| POST `/api/admin/snapshots/{snapshotId}/quality-check` | `governance:check` | SNAPSHOT |
| GET `/api/admin/quality-rules` | `governance:rule:view` | ScopedList |
| PATCH `/api/admin/quality-rules/{ruleId}` | `governance:rule:manage` | ScopedList + Service 强制受保护系统管理员 |
| GET `/api/admin/snapshots/{snapshotId}/quality-issues` | `governance:issue:view` | SNAPSHOT |
| GET `/api/admin/quality-issues` | `governance:issue:view` | ScopedList + SQL 范围下推 |
| PATCH `/api/admin/quality-issues/{issueId}/status` | `governance:issue:manage` | GOVERNANCE_ISSUE |
| PATCH `/api/admin/quality-issues/batch-status` | `governance:issue:manage` | ScopedList + Service 权限/归属预校验整批原子 |
| POST `/api/admin/quality-issues/{issueId}/assign` | `governance:issue:manage` | GOVERNANCE_ISSUE |
| PATCH `/api/admin/snapshots/{sid}/tables/{tableName}/governance-status` | `governance:rule:manage` | SNAPSHOT |
| PATCH `/api/admin/snapshots/{sid}/columns/{columnId}/governance-status` | `governance:rule:manage` | SNAPSHOT |
| PATCH `/api/admin/snapshots/{sid}/tables/{tableName}/batch-governance-status` | `governance:rule:manage` | SNAPSHOT |
| GET `/api/admin/snapshots/{snapshotId}/review-records` | `metadata:release:view` | SNAPSHOT |

**全局规则与表列状态的权限差异**：`governance:rule:manage` 不被改成系统管理员专属。全局规则启停（无 datasourceId）在 Service 内要求受保护系统管理员；表/列治理状态仍由“功能 + 目标负责源”放行。

**新增资源类型**：`GOVERNANCE_ISSUE`，归属以快照的真实 datasourceId 为准并校验与问题行一致。**问题行的 `datasource_id` 为空同样 409**（该列自 V11 起 `NOT NULL`，为空是事实缺失，不能回退成「信任快照」）。

**列表范围**：`listIssuesInDatasources(Collection<Long>, …)` 下推 `WHERE datasource_id IN (...)`；空集合返回空页。

**指定快照**：`?snapshotId=` 先解析真实归属——不存在 404、归属断链 409、不在传入的可见数据源集合内 403，通过后才分页查询。只塞进 `WHERE` 会让无权快照返回空页，把「无权」伪装成「没有数据」。

**批量原子边界（表述已修正）**：去重 → 一次读取全部事实 → 数量一致校验 → 逐项按真实 datasourceId 判定 → 任一失败整批拒绝且零修改，这是**权限与归属预校验整批原子**；**状态流转允许部分成功**并如实返回 `skipped`，不是「整批业务原子」。

**分派**：写入前校验责任人存在（404）且启用（400）；分派只写工作归属，不产生任何数据授权。

**表列归属**：经核查现有 Service 已校验（表按 snapshot+table 查、列校验 snapshotId、批量按 snapshot+table 查列），未重复添加，只补测试钉住。

**🔴 复审发现的 P0（已修）**：`IamS1AuthorizationAspect` 缺 `@Aspect`，Spring AOP 不代理已迁移端点，而旧 `@PreAuthorize` 已被删除、`/api/admin/**` 只要求 `authenticated()`——53 个已迁移端点当时对任何已登录用户开放。修复后新增三类只在「代理生效」时才成立的断言：真实容器中的 AOP 代理断言、`AspectJProxyFactory` 代理真实 Controller 的「拒绝时 Service 零调用」断言、资源表达式根变量与真实参数名的静态核对。

**前端（已补齐）**：`QualityDashboard.vue`（质量校验）、`IssueList.vue`（问题处理/批量/分派按 `governance:issue:manage` + 问题所属数据源；同表治理记录与返回发布流程按 `metadata:release:view`）、`StatusEditor.vue`（规则查看 `governance:rule:view`、全局规则启停仅系统管理员、表列状态 `governance:rule:manage`、读取 `metadata:view`）、`GovernanceFieldsView.vue`（脱敏候选 `security:mask:view` / `security:mask:manage`）、`ReleasesView.vue`（日志 `metadata:release:view`、开始检查 `governance:check`）。

**验证**：`mvn clean test` 438 passed / 0 failures / 0 errors / 0 skipped；前端 `vue-tsc -b && vite build` 通过，Vitest 8 文件 44 个通过。

**未完成**：权限与组织域 22 个 `IamS1*` 端点的注解迁移；`glossary:*` 混合语义定稿。

## B4 批次 5：语义中心接入（2026-09-20，未提交）

`GlossaryController` 14 + `KnowledgeDocController` 18 + `PromptTemplateController` 10 = **42 个方法级端点**
（以 `RequestMappingHandlerMapping` 实际枚举为准），例外清单 162 → **120**。

> 任务书基线写的是 43（Knowledge 19）。实际为 42：Knowledge 清单第 19 项是
> 「复核 Controller 实际映射」这条说明，不是端点；`KnowledgeDocController` 真实映射为 18 个方法
> （8 GET + 9 POST + 1 PUT），与例外清单删除条数一致，无漏项。

### glossary MIXED 语义冻结

`glossary:view` / `glossary:manage` / `glossary:approve` 保持 `FunctionScope.MIXED`：

- **未关联任何数据源** → 只校验功能，不推导任何数据源权限；
**范围状态是三态，不是「空集合即未绑定」**（修复轮定稿）：`UNBOUND`（确实没有关联关系）按全局功能；`BOUND`（关联完整）逐源校验；`BROKEN`（**存在**关联但实体丢失、读取失败或缺少 `datasource_id`）——查看列表不返回该术语，写/审核/删除/关联一律 409，术语表含 BROKEN 术语时改删术语表同样 409。术语只要有一条关联解析不出归属即整术语 BROKEN；术语表任一术语 BROKEN 即整表 BROKEN。
- **已关联** → 查看只返回负责源内的关联字段（已绑定术语至少一个可见关联源才返回，无可见源不返回）；
  写操作解析术语当前全部关联源并**逐源**校验，任一无权整体拒绝；
- **关联/解除字段** → 先校验术语现有源，再解析目标实体的**真实**数据源并校验，不采信前端传入；
- **术语表更新/删除** → 汇总其下全部术语关联源后逐源校验；
- **审核** → 独立 `glossary:approve`，不自动带来维护权或业务查询权。

接入方式：14 个端点统一 `@IamS1ScopedList`（功能级准入）；`@IamS1ScopedList` 的范围校验扩展为
**接受 RESOURCE 或 MIXED、仍拒绝 GLOBAL**；`@IamS1Global` / `@IamS1Resource` 继续拒绝 MIXED。
动态范围由 `GlossaryScopeService` 落实（复用 `IamS1AdminGuard` / `IamS1CapabilityService`，
不复制授权 SQL；一次关系查询 + 一次实体批量查询，无 N+1）。

### Knowledge 功能码与范围

| 功能码 | 端点 |
| --- | --- |
| `knowledge:view` | listDocs、getDoc、review-tasks、source-snapshots、versions、version detail、versions/diff、vector-tasks、preview-chunks |
| `knowledge:manage` | createDoc、updateDoc、submit-review、generate-draft、generate-from-snapshot |
| `knowledge:approve` | approve、reject |
| `knowledge:publish` | publish、rollback |

**只读 POST 例外**：`POST /api/admin/knowledge-docs/{id}/preview-chunks` 按 B0 冻结为只读预览，
使用 `knowledge:view`；覆盖扫描按精确「HTTP 方法 + 完整路径」登记（`READ_ONLY_POST_ENDPOINTS`）
并有 `readOnlyPostAllowlistStaysExact` 校验，禁止放宽成「所有 POST + view」或前缀匹配。

**新增 `IamS1ResourceType.KNOWLEDGE_DOCUMENT` + `KnowledgeDocumentResourceResolver`**：
文档不存在 404、`datasourceId` 缺失 409、当前版本归属与文档不一致 409、
来源快照缺失/归属不完整/属于其它数据源 409。

**列表范围**：`listDocsInDatasources` 下推 `WHERE datasource_id IN (...)`；空负责源返回空页；
显式筛选无权 `datasourceId` 直接 403。

**批量生成归属复核**：`generate-from-snapshot` 除 `SNAPSHOT` 解析器外，Service 内额外校验
「请求 datasourceId == 快照真实归属」，校验先于任何读取与 Python 调用。

**历史版本链归属**：文档级注解只解析文档与当前版本，而版本列表 / 版本详情 / 版本差异 / 审核记录 / 来源快照 / 回滚 / 索引任务都会读取历史版本。新增 `KnowledgeOwnershipValidator` 作为统一校验点：版本 `datasource_id` 必须存在且等于文档、来源快照若存在必须存在且同源、向量任务的 `datasource_id` 必须等于文档，任一不合法 409（`metadataSnapshotId` 为空是合法状态）。`listVersions` 校验全部版本、`getVersion` 校验单版本（版本差异与回滚都经此处）、`createVersion` 写入前校验客户端指定的 `snapshotId`、`listVectorTasksOfDocument` 校验任务归属；Document Resolver 复用同一校验器，并对「`currentVersion > 0` 但版本记录不存在」和「当前版本无归属」返回 409，`currentVersion` 为空或 ≤ 0 才按「尚无版本」显式放行。

**术语只能关联物理列**：`link-column` 强制 `MetadataEntity.TYPE_COLUMN`，否则 400。

**RAG 生命周期未改动**：`APPROVED → INDEXING → chunk/vectorize → verify → PUBLISHED → commit 后清理旧版本`；
「新版本验证成功前保留旧向量」不变。

### Prompt 三码独立

`prompt:view`（列表/效果/详情/版本）、`prompt:manage`（更新/提交/启停/回滚）、
`prompt:approve`（通过/驳回）全部 `@IamS1Global`，互不包含；`enabled` 从
「manage 或 approve」收紧为仅 `manage`。查看不返回密钥；`/internal/prompts/**` 的内部令牌边界未改动。

### 验证

- Java `mvn -o clean test`：**512 passed / 0 failures / 0 errors / 0 skipped**（批次 4 后为 438；批次 5 首轮 +47，复审修复轮 +27）；
- 前端 `vue-tsc -b && vite build` 通过，Vitest **9 文件 59 个通过**（批次 4 后为 8 文件 44 个）；
- 三个新 Controller 旧 `@PreAuthorize` 计数为 0；批次 5 生产代码无旧权限表 / 旧 authority / 兼容兜底引用；
- 已迁移 Controller 9 个，全部为真实 AOP 代理；例外清单 162 → 120（减少 42）；
- `git diff --check` 通过。

**未完成**：权限与组织域 22 个 `IamS1*` 端点的注解迁移；批次 6（运营与平台）未开始；
未执行 migration、bootstrap、服务/浏览器验收或正式切换。
