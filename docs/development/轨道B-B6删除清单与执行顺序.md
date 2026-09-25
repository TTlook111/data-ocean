# 轨道 B：B6 删除清单与执行顺序

> 生成日期：2026-09-25。基于对当前工作区的只读盘点，每项都有 `文件:行号` 依据。
>
> 本文只回答"删什么、按什么顺序、删的时候要同步改什么"。**不构成执行授权**：
> `docs/development/后续开发.md` 中「B6 必须单独获得授权」仍然有效，未获授权前不执行这些删除。
>
> 按批次提交，**每个批次独立跑一次全量测试**，不要合并成大提交。

---

## 执行进度（2026-09-25）

| 批次 | 状态 | 证据 |
| --- | --- | --- |
| 批次 0 修导航 404 | ✅ 完成 | 前端测试 73 通过、构建 exit 0；实测 `router.resolve('/admin/access')` → `name=not-found`，修复后指向 `/admin/access/iam` |
| 批次 1 前端孤儿 | ✅ 完成 | 删 7 个文件 + `api/admin/user.ts` 的 14 个角色/权限函数（保留用户/部门相关 14 个）+ 更新读旧文件的测试；测试 73 通过、构建 exit 0 |
| 批次 2 后端旧权限管理端 | ✅ 完成 | 删 15 个文件（3 个 Controller + 2 组 Service/Impl + 定时任务 + 2 个 Entity + 2 个 Mapper + 2 个 DTO + 1 个测试）；同步删 13 条 `IamS1EndpointExemptions` 条目、从 `WildcardAuthorizationAnnotationTest` 移除 3 个类；Java 测试 **601 通过**（603 − 2） |
| 批次 3 旧问数链路（Java） | ✅ 完成 | 删 52 文件（−5201 行）：旧问数链路 + 旧权限计算 + 旧数据源授权 + 旧角色权限配置；脱敏能力迁到 `common/security`。Java 测试 **585 通过**（601 − 16） |
| 批次 3 旧问数链路（Python） | ✅ 完成 | 删 42 文件（−6829 行）：整个旧 Agent 包 + sandbox 旧模块 + `infra/sse.py` + 11 个旧测试文件。Python 测试 **105 通过**（228 − 123，逐文件核对吻合） |
| 批次 4 数据库删表 | ✅ 完成 | 新增 `V58__b6_drop_legacy_permission_tables.sql`，本机库已应用（`Successfully applied 1 migration ... now at version v58`，失败数 0）。6 张旧权限表已删除；`permission_change_log`（0 行）与 `access_approval_request`（**2 行历史完好**）按冻结清单保留只读；`iam_s1_*` 仍为 14 张。表数 56 → 70 → 64 算术吻合。Java 测试 **585 通过** |
| 批次 5 文档 | ✅ 完成 | `frontend/CLAUDE.md` 的权限章节重写（原章节仍在教已失效的 `auth.user?.permissions?.includes()`）；`CLAUDE.md`、`AGENTS.md` 的状态表/Track B 段落/测试基线；整改计划的状态与残余风险；`后续开发.md` 的队列改为「B6 已完成，仅剩会话机制」 |

**B6 全部完成。** 唯一未处理的遗留项是冻结清单第 497/498 行的会话机制替代物，属独立任务（见 `后续开发.md`）。

### 批次 5 的一个约束：文档里有四句话被守护测试断言

`IamS1B5PreparationStaticTest` 会读取本文档之外的几个文件并断言其内容，改写时**必须保留原文**，否则测试失败：

| 文件 | 必须包含 |
| --- | --- |
| `后续开发.md` | `V53 永久不再使用`、`B6 必须单独获得授权`、`只删除冻结清单中的旧权限专用入口、代码和表`、`P9 若实施必须使用 V58 或更高的未占用版本`、`独立 MySQL 恢复演练` |
| `轨道B-B0权限清单与决策冻结.md` | `## 7. B6 删除清单`、`DatasourcePermissionController`、`sys_role_permission` |

其中 `P9 若实施必须使用 V58 或更高的未占用版本` 在 V58 被占用后已经过时，但**不能改字面**——
本次的做法是保留原句并在其后补一句说明实际应从 V59 起选版本。

### 批次 4 执行前的两项核对（发现一个运行期炸弹）

1. **外键**：全库 `information_schema` 实测 **0 外键、0 视图、0 触发器**，因此 DROP 无依赖顺序问题。
2. **运行期 SQL 引用**（关键）：MyBatis 的 SQL 是**运行期解析**的，编译不会报错。
   扫描发现 `DatasourceMapper` 里 `selectAccessibleByUserId` 与 `selectAccessibleMultiDimension`
   仍带 `JOIN datasource_access` 的注解 SQL，且全仓库**无任何调用方**（旧权限体系的死方法）。
   已在批次 4 内一并删除这两个方法——否则删表后一旦被调用就是运行期错误。
   `DatasourceSimpleVO` 因仍被 `DatasourceAdminController.listSimple()` 使用而保留。

### 批次 3 执行中发现的两处「不能删」

原计划把 Python 的 `agent/` 整包删除，实测发现两处必须保留：

1. **`POST /internal/query/context-summary` 仍被 S1 链路调用**。链路是
   `IamS1QueryServiceImpl` → `ConversationContextSummaryService` → `ConversationSummaryClientImpl`
   → Python `/internal/query/context-summary`（原在 `agent/router.py`）。
   因此该端点连同 `agent/conversation_summary.py` 与两个模型、以及本地兜底模板
   `agent/prompts/conversation_summary.j2` 一起**迁到新的 `dataocean/conversation/` 包**，
   **路由路径保持不变**以免为纯搬迁而改 Java 侧客户端。
2. **`iam_s1` 从 sandbox 复用** `executor.py`、`config.py`、`rules/{depth,function,limit}_rule.py`，
   从 `agent/` 只复用 `sse.py`——但进一步核实发现 `iam_s1/router.py` 的 `from dataocean.agent import sse`
   是**未使用的死 import**（它自己用手写 `_event()` + `StreamingResponse` 组流）。
   因此 `agent/sse.py` 与只被它引用的 `infra/sse.py` 一并删除。

### 批次 3 的执行顺序（实际）

与 §3.5 的修正一致：**先删旧链路 → 再删旧重载 → 再搬脱敏**。
Java 与 Python 都在同一步内完成，中间态无跨服务契约不一致——
因为 Java 侧删除后已无任何代码调用 Python 的 `/internal/query/execute`，
该端点成为无调用方的死端点，两个服务之间仍然一致。

### 执行中修正的两处归类错误

原文把以下两个对象放在批次 2，实际它们被**属于批次 3 的、仍然活着**的类引用，已改归批次 3：

- `DatasourcePermissionGrantDTO` —— 被 `DatasourcePermissionService` 使用
- `AccessPolicyVO` —— 被 `DatasourceAccessPolicyMapper:62 selectPolicies` 使用

另：`WildcardAuthorizationAnnotationTest` 不能被"整个删除"当作批次 2 的动作——它覆盖 28 个 Controller，批次 2 只从中移除了已删的 3 个。它记录的实质断言（授权表达式含通配 `'*'`）在批次 2 之后只剩 `RoleController` 与 `PermissionController`，故**整体删除放到批次 3**。

---

## 0. 两个必须先知道的现状

### 0.1 旧权限管理端已经"不可达"，但没有被删

那 5 个旧 Controller 上共 17 个 `@PreAuthorize`，全部形如 `hasAnyAuthority('security:manage', '*')`。
但 `common/security/UserDetailsServiceImpl.java:46-47` 现在**只授予 `AUTHENTICATED_USER`**
（注释明写"业务授权不放入 Spring authorities"），而 `IamS1AuthorizationAspect` 只做决策、不授予权限。

→ 这 17 个端点对**所有人（含管理员）返回 403**。对应的前端页面全部不在路由表里。
这不是"还在用的旧功能"，是**已经断掉但尸体还在**。

### 0.2 但旧**问数**链路仍然活着

| 位置 | 情况 |
| --- | --- |
| `module/query/controller/QueryController.java` | **没有任何授权注解**，任何登录用户可达 |
| `QueryController.java:47,76` | 依赖 `DatasourceAccessService` / `DatasourcePermissionService` |
| `QueryTaskServiceImpl.java:54,106` | `permissionCalculator.calculate(userId, datasourceId)` |
| `PythonAgentClientImpl.java:95,107,367-390` | 同上，并把 `PermissionContextVO` 转成 `userPermissions` 平铺字段发给 Python |
| `module/permission/scheduler/ApprovalExpiryScheduler.java:30-32` | `@Scheduled(cron = "0 0 * * * *")`，`matchIfMissing = true` → **每小时在跑** |

前端已 100% 走 `api/iamS1.ts`（`useQuerySubmit.ts:7-15`），`api/query.ts` 已无 import。
也就是说：**接口还开着，但界面已经不调了**。这是"删除"而非"迁移"的对象。

---

## 批次 0：先修用户可见故障（不删任何东西）

**当前一级导航「权限与组织」点击后落到 404。**

| 位置 | 问题 | 处置 |
| --- | --- | --- |
| `frontend/src/components/admin/AdminDomainNav.vue:28` | `access` 域的 `path` 是 `/admin/access`，而路由表只有 `/admin/access/iam` 等三个子路径；其余 5 个域用的都是工作区路径，只有它是裸域路径 | 改为 `/admin/access/iam` |
| `frontend/src/components/admin/AdminDomainNav.vue:58-60` | `targetFor()` 注释写"进入该域第一个工作区"，实现却直接用 `domain.path` | 按注释修正实现，或修正注释 |
| `frontend/src/utils/adminNavigation.ts:69-73` | `QUERY_PERMISSION_NOT_CONFIGURED` 的 readiness 动作仍指向 `/admin/access` | 改为 `/admin/access/iam` |

**同步补测试**：现有守卫只覆盖 `router/index.ts` 与 `router/adminNavigation.ts`
（`views/admin/access/IamS1OfficialPages.test.ts:32-43`、`utils/adminNavigation.test.ts:22-25`），
**`AdminDomainNav.vue` 与 `utils/adminNavigation.ts` 是漏网的**。建议加一条"每个一级域的 path 必须能在路由表中解析到"的断言。

---

## 批次 1：前端孤儿文件（无路由、无 import，可整批删）

| 文件 | 行数 | 现状证据 |
| --- | --- | --- |
| `frontend/src/api/admin/permission.ts` | 195 | 仅被下面两个孤儿页引用 |
| `frontend/src/views/admin/permission/AccessControl.vue` | 995 | 不在 `router/index.ts`，全仓仅自引用 |
| `frontend/src/views/admin/permission/AccessApprovalView.vue` | 463 | 同上 |
| `frontend/src/views/admin/user/OrganizationView.vue` | ~340 | 无路由；仅被守卫测试字符串读取 |
| `frontend/src/views/admin/user/RoleList.vue` | ~450 | 仅被 `OrganizationView.vue:24` 引用 |
| `frontend/src/views/query/IamS1QueryView.vue` | ~575 | 无 import（`/query/iam-s1` 只 redirect 到 `/query`） |
| `frontend/src/api/query.ts` | 109 | 无任何 import |

**必须同步改的引用**（不改则测试/构建失败）：

1. `frontend/src/views/admin/access/IamS1OfficialPages.test.ts:55-65` —— 这条用例 `readFrontend()` 读取
   `src/views/admin/user/OrganizationView.vue` 的内容做断言。文件删除后必须整条删除该用例。
2. `frontend/src/api/admin/user.ts` —— 删除 14 个只服务旧页面角色/权限的函数：
   `listRoles` `createRole` `updateRole` `deleteRole` `listRolePermissionIds` `updateRolePermissions`
   `listPermissionsTree` `listPermissions` `createPermission` `updatePermission` `deletePermission`
   `listRoleUsers` `assignRoleToUser` `removeRoleFromUser`。
   **保留** `UserList.vue` / `DepartmentTree.vue` 仍在使用的函数（`IamS1OrganizationView.vue:14-15` 在用这两个组件）。

**验证**：`cd frontend && npm run test:run`（Vitest 67→ 至少 67，删用例后数量减少属预期）与 `npm run build`。

---

## 批次 2：后端旧权限管理端（其唯一调用方是那 3 个已 403 的 Controller）

**先处理定时任务**：删除 `module/permission/scheduler/ApprovalExpiryScheduler.java`，
或将其 `@ConditionalOnProperty` 置为 `havingValue="true"` 去掉 `matchIfMissing`。
不处理它，`AccessApprovalService` 就删不掉——**它是唯一以定时任务形式在后台运行的旧权限组件**。

可删对象（唯一调用方是上述 3 个 Controller 或该定时任务）：

| 对象 | 路径 |
| --- | --- |
| `AccessApprovalController` | `module/permission/controller/` |
| `AccessPolicyController` | 同上 |
| `DatasourcePermissionController` | 同上 |
| `AccessApprovalService` + `Impl` | `module/permission/service/` |
| `AccessPolicyService` + `Impl` | 同上 |
| `AccessApprovalRequest` + `AccessApprovalRequestMapper` | `entity/`、`mapper/` |
| `PermissionChangeLog` + `PermissionChangeLogMapper` | 同上（**表保留，只删写入路径**） |
| `AccessPolicyCreateDTO` `AccessPolicyBatchDTO` `DatasourcePermissionGrantDTO` | `entity/dto/` |
| `AccessPolicyVO` | `entity/vo/` |

**不属本批次**（被仍然活着的旧问数链路牵连，见批次 3）：
`DatasourceAccessPolicy` + `DatasourceAccessPolicyMapper`（`PermissionCalculatorImpl:13,52` 在用）、
`DatasourcePermissionVO`、`PermissionValidationSupport`（`DatasourcePermissionServiceImpl:55` 在用）、
`AccessType` 枚举（唯一调用方是 `PermissionValidationSupport:57`）、`SubjectType`、
`DatasourcePermissionService(+Impl)`、`DatasourceAccessService(+Impl)`。

> 判断标准是**唯一调用方**：只要某个类还挂在 `PermissionCalculatorImpl` / `QueryController` /
> `DatasourcePermissionServiceImpl` / `DatasourceAccessService(Impl)` 这条活链上，就归批次 3，
> 不能因为"它是旧权限的"就提前删。

**必须同步改的引用**：

1. `src/test/java/com/dataocean/module/permission/s1/coverage/IamS1EndpointExemptions.java` ——
   每个被删的旧 Handler 都要删掉对应豁免条目，否则
   `IamS1EndpointCoverageTest.java:318`（`everyAdminEndpointIsAnnotatedOrExplicitlyExempted`）
   与 `:338`（`exemptionsMustReferenceRealHandlers`）会失败。**这是每批次都要做的动作。**
2. 删除旧权限专属测试：`AccessApprovalServiceImplTest`、`WildcardAuthorizationAnnotationTest`
   （后者 `:19-21` import 三个旧 Controller、`:54,63` 断言旧通配表达式）。
3. `PermissionChangeLog` 的 2 个写入点：`AccessApprovalServiceImpl.java:130`、`AccessPolicyServiceImpl.java:389`
   —— 随类删除即可，但**表和历史行保留**。

---

## 批次 3：旧问数链路整条退役（最大，必须原子切换）

**这是唯一有技术风险的一批**：Java 契约与 Python 必填字段必须同时改，否则会出现 Java 调 404 或 400。

### 3.1 等价性核对：**已完成，无缺口**（2026-09-25）

逐端点对比结论：`IamS1QueryController`（`/api/iam-s1/query`）是旧 `QueryController`（`/api/query`）的**超集**。

| 旧 `QueryController` | 新 `IamS1QueryController` | 行号 |
| --- | --- | --- |
| `POST /ask` | ✔ | `IamS1QueryController.java:28` |
| `GET /tasks/{taskId}` | ✔ | `:39` |
| `POST /tasks/{taskId}/cancel` | ✔ | `:54` |
| `POST /tasks/{taskId}/feedback` | ✔ | `:109` |
| `GET /conversations/{conversationId}/messages` | ✔ | `:70` |
| `GET /conversations` | ✔ | `:65` |
| `DELETE /conversations/{conversationId}` | ✔ | `:78` |
| `GET /history` | ✔ | `:60` |
| — | 多出 `tasks/{taskId}/sql`、`tasks/{taskId}/export`、`export.csv` | `:44`、`:84`、`:89` |

→ **退役旧链路不会丢失任何能力。** 批次 3 的功能前置已满足，剩下的只有技术顺序（Java 契约与 Python 必填字段同步改）。

### 3.0 边界核对结论（2026-09-25，逐项实测）

| 核对项 | 结论 |
| --- | --- |
| `QueryTaskService` / `QueryTaskServiceImpl` | **只被旧链路引用**（`QueryController`、`QuerySseController`、`PythonAgentClientImpl`）。`IamS1QueryServiceImpl` **不**使用它（它只用 `QueryTaskMapper`）→ 可删 |
| `QuerySseController` | **只被 `PythonAgentClientImpl` 引用** → 可删。⚠️ 注意别被 `IamS1QuerySseController` 的**子串匹配**误导（我第一次就误判成了共享组件） |
| `DatasourceReadinessServiceImpl` | **已完全切到 S1**（`IamS1DataGrantMapper` + `IamS1DataAuthorizationResolver`），不再依赖旧权限服务 → 不在删除范围 |
| `UserServiceImpl` | **已完全切到 S1**（`IamS1UserRoleMapper` 等），不用旧 `RoleService`/`RoleMapper`/`SysRole` → 旧角色类可删 |
| `common/config/CacheConfig` | 通用 Spring Cache，**保留**（见 3.6） |
| `DatasourceAccessServiceImplTest` | 直接 `new DatasourceAccessServiceImpl(...)` 并 mock 旧 Mapper → 随旧类删除 |

**测试删除清单**（批次 3）：`PermissionCalculatorImplTest`、`DatasourceAccessServiceImplTest`、
`RoleServiceImplTest`（若确为旧 `RoleServiceImpl` 的用例）、`WildcardAuthorizationAnnotationTest`（见批次 2 说明）。

### 3.2 Java 侧

`QueryController`、`QuerySseController`、`QueryTaskServiceImpl` 的旧分支、
`PythonAgentClientImpl`、`PermissionCalculator` + `Impl`、`PermissionContextVO`、
`DatasourceAccessService` + `Impl`、`DatasourcePermissionService` + `Impl`、
`DatasourceUserController` 及其 `/api/datasources`、`DatasourceAccessMapper`、
`DatasourceMapper` 中的死方法（`:159 selectAccessibleByUserId`、`:181 selectAccessibleMultiDimension`）、
`common/config/CacheConfig` 的 Caffeine 权限缓存（仅 `PermissionCalculatorImpl:61-62` 使用）、
`RoleController` + `PermissionController` + `RoleServiceImpl` + `PermissionChangedEvent`（14 处发布）。

### 3.3 Python 侧

`agent/` 整包（约 2,870 行）由 `main.py:139-144` 注册为 `/internal/query`，
唯一生产调用方是 `PythonAgentClientImpl.java:119`。

> **修正一处盘点结论**：`sandbox/schema.py`、`sandbox/validator.py`、`sandbox/rewriter.py`、
> `sandbox/rules/{star,statement,table}_rule.py`、`sandbox/router.py` 的 `/validate` 与 `/execute`
> **不能"现在就删"**——`agent/nodes/sql_validator.py:11,12` 仍在用 `validator`/`rewriter`，
> 而旧 Agent 链仍在服务。它们必须与 Java 侧同时下线。

**必须保留**（新链路在用）：`sandbox/rules/{depth,function,limit}_rule.py`（`iam_s1/sql_security.py:16,507`）、
`sandbox/executor.py`、`sandbox/pool_manager.py`、`sandbox/config.py`、`agent/sse.py`（`iam_s1/router.py:10`）。

### 3.4 契约同步（两边必须同一次改完）

- Java `AgentExecuteRequest.java:40 userPermissions` ↔ Python `agent/schema.py:96-98` 的
  `user_permissions`（**无默认值 = 必填**）。删 Java 侧必须同步删 Python 侧。
- 旧平铺字段 `allowedTables` / `tableScopeMode` / `deniedColumns` / `rowFilters` / `maskColumns`
  在 Java `PythonAgentClientImpl.java:367-390` 与 Python `agent/schema.py:29-51`、`sandbox/schema.py:16-48` 成对存在。
- **`maskedFields` 是新契约字段**（`IamS1QueryServiceImpl.java:789`），与旧 `maskColumns` 不同名，**不要误删**。

### 3.5 `DataMaskingService` 必须拆分，不能整类删 —— 且**顺序要反过来**

冻结清单第 7 行要求："通用掩码算法可由新服务复用，旧输入接口必须删除，不能整类误删业务安全能力。"
新链路 `IamS1QueryServiceImpl.java:93` 依赖的是旧包位置的
`com.dataocean.module.permission.service.DataMaskingService`（用 `maskResultByFields`、`maskValue`）；
旧权限专属重载 `maskResult(List, List<PermissionContextVO.MaskColumnItem>)` 的唯一调用方是
`QueryTaskServiceImpl.java:116`。

**修正**：原先写的是"先把通用算法迁到新包，再删旧重载"，这个顺序有问题——
只要旧重载还在，接口就仍 import `PermissionContextVO`（`module/permission` 的类），
而目标包在 `common/` 下，会造成 `common → module` 的反向依赖。

**正确顺序**：
1. **先**删旧问数链路（本节的 3.2），使 `maskResult` 失去唯一调用方；
2. 删除 `maskResult` 重载，接口不再 import `PermissionContextVO`；
3. **再**把 `DataMaskingService` / `DataMaskingServiceImpl` / `MaskStrategy` 整体迁到
   `common/security/`（脱敏属于数据保护能力，与 `common/security` 现有的认证组件同属安全域）；
4. 改 `IamS1QueryServiceImpl.java:93,278,732,823` 的引用（现为全限定名）与
   `IamS1QueryServiceImplTest.java:82` 的 mock 目标。

### 3.6 明确**不删**：`common/config/CacheConfig`

它是**通用**的 Spring Cache（Caffeine）配置，没有权限专属的缓存名；冻结清单第 2 条也告诫
"非权限的...通用 AI/Embedding 实例缓存...不因名称相近而误删"。`PermissionCalculatorImpl:60-67`
里那个**私有的** Caffeine 权限缓存随该类一起删除即可。
（另注：该项目自己的 `CLAUDE.md` 规定查询路径缓存只用 Redis；`CacheConfig` 目前无人使用，
属独立的清理议题，不在 B6 范围内。）

---

## 批次 4：数据库（受 B5 前置条件约束，现在不能执行）

- 13 个 migration 中**没有任何 `DROP TABLE`**，全部前向增量 → 删表必须新增 migration。
- **必须 ≥ V58**。V53 永久不使用（见 B0 §7 与 `后续开发.md`）。
- 要 DROP 的 **6 张**：`sys_permission`、`sys_role`、`sys_user_role`、`sys_role_permission`、
  `datasource_access`、`datasource_access_policy`。
- **保留为只读历史（2026-09-25 决定，与冻结清单一致）**：`permission_change_log`、`access_approval_request`。
  只删写入路径，不删表和历史行——它们是"谁在何时给谁什么权限"的审计证据。
**前置条件（2026-09-25 重新评估）**

项目负责人明确：本项目在本地开发环境运行、仍在开发过程中、无业务数据。据此重新分类 B5 的两个缺口：

| B5 缺口 | 原用途 | 在本地开发环境下 |
| --- | --- | --- |
| 独立 MySQL 恢复演练 | 证明备份可恢复，使生产切换可回退 | **不再是门禁**——数据可弃，备份没有意义 |
| 双用户负向场景（启用但无 S1 绑定 / 只有旧权限） | 验收证据 | **仍建议做**——它测的是**新系统是否会正确拒绝**，与数据无关。删掉旧表后就失去对照组 |

B0 §7 那句"删除必须在 B5 正式切换、真实复验通过且输出本表复核记录后执行"是为**生产环境**写的。
本地开发环境执行时需要的是：项目负责人的明确授权（见下）+ 负向场景要么跑过、要么明确豁免。

---

## 批次 5：文档

| 文件 | 需要改什么 |
| --- | --- |
| `frontend/CLAUDE.md:28,48` | 仍在教 `auth.user?.permissions?.includes('xnxx:manage')` 这套**已失效**的写法，"权限模型"整章需重写 |
| `CLAUDE.md` / `AGENTS.md` | 状态表、测试基线、旧权限对象清单 |
| `docs/development/DataOcean后台重构状态与整改计划.md` | 状态行 |
| `docs/development/后续开发.md` | 队列（注意不要删除「B6 必须单独获得授权」，有守护测试断言它） |

---

## 每批次都要遵守的守卫

删任何旧对象时，以下测试会立刻失败——它们是**设计如此**，不要绕过，要同步更新：

| 守卫 | 断言什么 |
| --- | --- |
| `IamS1EndpointExemptions` ↔ `IamS1EndpointCoverageTest:318,338` | 每个豁免条目必须对应真实存在的 Handler；每删一个旧 Handler 就要删对应豁免 |
| `IamS1FunctionCatalogTest:71-82` | S1 包内**不得出现** `sys_role`/`sys_permission`/`datasource_access*`/`DatasourceAccessService`/`PermissionCalculator`/`Caffeine` |
| `IamS1B5PreparationStaticTest:62-79,97-142` | 同上，静态门禁 |
| `views/admin/access/IamS1OfficialPages.test.ts` | S1 正式页面不得引用 `listRoles`/`listPermissions`/`updateRolePermissions`/`assignRoleToUser` |
| `views/query/IamS1FormalEntry.test.ts:34-36` | 正式查询入口不得引用 `api/query` 或 `/api/query/` |
| `IamS1B5PreparationStaticTest:209` | `后续开发.md` 必须写明「B6 必须单独获得授权」 |

---

## 明确不删（冻结保留）

- `MaskStrategy` 枚举、`DataMaskingService` 的通用算法（迁包后保留）
- `sandbox/rules/{depth,function,limit}_rule.py`、`sandbox/executor.py`、`sandbox/pool_manager.py`、`sandbox/config.py`、`agent/sse.py`
- `QueryTaskMapper`、`ConversationService`、`ConversationContextSummaryService`、`AuditLogService` 等业务组件
- 表 `permission_change_log`、`access_approval_request`（只读历史）
- `tokenVersion` claim 与 `jwt:blacklist:{jti}` / `user:token-version:{userId}` —— **替代会话机制尚未建立，不能先删**（B0 §7 第 497/498 行只完成了一半）
- 账号、部门、数据源、元数据、知识、会话、审计等业务数据

---

## 仍未解决的前置

1. **B6 的明确授权**——见开头的说明，未获授权前不执行批次 1 及以后。
2. 双用户负向场景（启用但无 S1 绑定 / 只有旧权限）未做。本地开发环境下不再是硬门禁，但**建议在删表前跑一次**：它测的是新系统失败时是否"关闭"而非"放行"，删掉旧表后就失去对照组。
3. `authProtocolVersion` / `sessionEpoch` / `iam-s1:session:*` 在 Java 主代码中为 0 处引用，旧会话键（`jwt:blacklist:{jti}`、`user:token-version:{userId}`）仍是唯一机制。**替代物建立前不要删。**
4. ~~本机 `dataocean` 库停在 Flyway V52~~ —— **已于 2026-09-25 迁移到 V57**（14 张 `iam_s1_*` 表已建立）。触发方式是启动 Java；本地数据可弃，故无需备份。
