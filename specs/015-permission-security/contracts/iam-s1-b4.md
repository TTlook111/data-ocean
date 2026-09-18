# IAM-SIMPLE-1 B4 页面与业务接入合同

状态：B4 代码已实现并完成一轮 P1 复核修复（未提交、未推送）。范围结论见“B4 复核后的范围结论”：这是“权限与组织域 + 独立安全问数入口”的 B4 初版，**不是完整 B4，不能据此进入 B5 验收**。V57 只新增 S1 前向表，未执行真实数据库升级；未启动服务、未做浏览器验收、未执行 bootstrap、未正式切换、未清理旧权限。

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
| 选择对象 | `GET /api/iam-s1/subjects` | `security:permission:view`（全局），只返回必要名称 |
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
| 审批队列 | `GET /api/iam-s1/access-requests/queue` | `security:approval:view` + 负责源 |
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

