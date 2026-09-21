# DataOcean IAM-SIMPLE-1 鉴权接入设计

## 1. 文档目的

本文定义 IAM-SIMPLE-1（以下简称 S1）在 Java 后台接口中的统一接入方式，解决六个后台业务域接入过程中重复调用 `IamS1AdminGuard`、资源归属解析分散、接口遗漏权限声明和列表范围过滤不一致的问题。

本文只定义 S1 的接口动作准入、资源范围解析和业务 Service 边界，不改变以下已冻结规则：

- 新权限不读取、不映射、不回填旧角色权限、旧 JWT authority、旧数据授权或旧权限缓存；
- 一个 HTTP 请求只允许命中一套权限实现；
- 后台功能和负责源必须在同一个启用的用户角色绑定上同时成立；
- 业务查询权来自独立数据授权，不由后台负责源推导；
- 前端路由、Tab 和按钮隐藏不是安全边界；
- Java 仍是管理生命周期、持久化、审计和最终响应保护的责任方；
- 查询链的表列授权、记录条件、SQL AST 和最终脱敏继续使用 B2/B3 已建立的专用链路。

## 2. 阶段结论

本设计必须在 **B4** 完成，不能留到 B5。

具体安排为：

1. 在 B4 六域接入中增加前置子阶段 **B4-A：S1 鉴权接入框架**；
2. 先实现注解、切面、资源解析器、覆盖扫描测试；
3. 把已经完成的“工作台”和“数据接入”两个批次迁移到该框架，验证全局功能、直接数据源、列表裁剪和写操作；
4. 验证通过后，再继续 B4 批次 3～6；
5. B4 全部代码、自动化测试和静态复审通过后，才允许进入 B5；
6. B5 只负责维护窗口内的部署、migration、bootstrap、新角色/负责源/数据授权初始化、真实接口与浏览器验收和正式切换，不再引入新的鉴权框架。

原因：如果先让剩余 117 个端点继续显式散布 Guard 调用，再在 B5 前重构，会扩大重复修改和漏检范围；如果拖到 B5 才引入切面，则会把架构改造与真实迁移、初始化、切换混在同一个高风险窗口中。

## 3. 总体设计

```text
HTTP 请求
  -> Spring Security：只确认登录身份并提供 userId
  -> S1 鉴权切面：校验动作功能与资源负责范围
  -> Application/Domain Service：数据范围下推、批量逐项校验、业务约束
  -> Repository/Mapper：只读取或修改获准范围
  -> 响应保护：字段隐藏、脱敏、SQL/导出能力和审计
```

职责划分：

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| Spring Security | 认证、JWT 有效性、提供当前用户 ID | 不把旧 JWT authority 作为 S1 授权事实 |
| S1 注解与切面 | 动作功能准入、单资源归属解析、同绑定“功能 + 负责源”校验、统一拒绝 | 不拼业务查询条件，不替代领域规则 |
| `IamS1AuthorizationResolver` / `IamS1AdminGuard` | 唯一 S1 后台授权算法和中文拒绝原因 | 不感知具体 Controller 参数布局 |
| 资源解析器 | 把 snapshot、entity、task、document 等资源 ID 安全解析到 datasourceId 等归属事实 | 不接受调用方伪造的 datasourceId 覆盖真实归属 |
| Service | 列表范围、分页下推、批量原子校验、审批子集、状态机、本人边界 | 不读取旧权限事实作为兼容兜底 |
| B2/B3 查询安全链 | 数据授权、记录条件、字段 usage、SQL AST、revision、最终脱敏 | 不由通用后台切面简化替代 |

## 4. 注解模型

### 4.1 全局功能

全局功能不依赖负责源，但仍从 S1 角色绑定实时计算：

```java
@IamS1Global("organization:user:view")
public Result<?> listUsers(...) {
    // 列表字段与业务范围仍由 Service 控制
}
```

适用示例：

- `admin:workbench:view`；
- `organization:user:view`；
- `organization:role:view`；
- `organization:department:view`；
- 其他在 B0 冻结为“全”的功能。

### 4.2 资源范围功能

资源范围功能必须同时解析资源归属，并在同一个用户角色绑定上校验功能与负责源：

```java
@IamS1Resource(
        function = "datasource:manage",
        resourceType = IamS1ResourceType.DATASOURCE,
        resourceId = "#id"
)
public Result<?> updateDatasource(Long id, ...) {
    ...
}
```

间接资源示例：

```java
@IamS1Resource(
        function = "metadata:release:view",
        resourceType = IamS1ResourceType.SNAPSHOT,
        resourceId = "#snapshotId"
)
public Result<?> getSnapshot(Long snapshotId) {
    ...
}
```

### 4.3 约束

- 注解中的功能码必须来自固定 54 码目录；应用启动或自动化测试发现未知码时失败；
- `resourceId` 只允许受控的方法参数表达式，不允许执行任意 Bean 方法；
- 参数为空、资源不存在、资源归属不完整、解析异常时默认拒绝；
- 同一方法不能同时声明旧 `@PreAuthorize` 权限和 S1 注解；
- 不允许通过注解参数直接声明“跳过负责源”；全局或资源型必须由固定目录和接口消费清单决定；
- 注解只做准入声明，不能把动态 SQL、字段列表或审批范围放进表达式。

## 5. 切面执行规则

新增一个 S1 权限切面，内部继续复用现有 `IamS1AdminGuard`，不得复制授权算法。

### 5.1 全局功能流程

1. 从 `UserContext` 获取当前用户 ID；
2. 验证功能码属于固定目录且被定义为全局功能；
3. 调用 `IamS1AdminGuard.requireGlobalFunction()`；
4. 失败时返回统一 401/403 和中文原因；
5. 记录不包含秘密值的拒绝审计。

### 5.2 资源范围流程

1. 获取当前用户 ID；
2. 解析注解中的方法参数；
3. 根据 `resourceType` 选择固定资源解析器；
4. 从真实资源事实解析 datasourceId；
5. 调用 `IamS1AdminGuard.requireDatasourceFunction()`；
6. 授权通过后才进入业务方法；
7. 解析失败、事实冲突或授权事实读取失败时 fail-closed。

### 5.3 切面顺序

- 身份认证在 S1 切面之前；
- S1 动作准入在业务事务与写操作之前；
- 业务 Service 内的对象状态、并发锁和子集检查不能因切面而删除；
- 操作日志切面仍记录最终成功或失败结果，不负责授予权限；
- 禁止依赖同类内部调用触发 Spring 代理；安全入口应放在 Controller 调用的公开 Service 方法或 Controller 入口，并由覆盖测试保证所有直接 API 均受保护。

## 6. 资源解析器

### 6.1 固定接口

```java
public interface IamS1ResourceResolver {
    IamS1ResourceType supports();
    IamS1ResolvedResource resolve(Object resourceId);
}
```

`IamS1ResolvedResource` 至少包含：

- 资源类型；
- 资源 ID；
- datasourceId；
- 可选的 ownerUserId、snapshotId、tableName；
- 解析使用的 S1/业务事实版本信息。

### 6.2 第一批资源类型

| 资源类型 | 输入 | 归属解析 |
| --- | --- | --- |
| `DATASOURCE` | datasourceId | 直接读取启用的数据源身份事实 |
| `SNAPSHOT` | snapshotId | snapshot → datasourceId |
| `METADATA_ENTITY` | entityId | entity/snapshot → datasourceId |
| `METADATA_COLUMN` | columnId | column/table/snapshot → datasourceId |
| `KNOWLEDGE_DOCUMENT` | documentId | document → datasourceId |
| `QUERY_TASK` | taskId | task → datasourceId + ownerUserId |
| `AUDIT_LOG` | auditId | audit → datasourceId |
| `LINEAGE_RELATIONSHIP` | relationshipId | relationship → source entity → datasourceId；目标端由 Service 另验 |
| `COLUMN_META` | columnMetaId | db_column_meta → datasourceId |
| `FIELD_TAG_RELATION` | tagId | field_tag → column → datasourceId |
| `FEEDBACK_REVIEW` | feedbackId | feedback → column / query task → datasourceId |

新增资源类型必须同步增加：解析器、成功测试、不存在测试、跨源伪造测试和接口消费矩阵记录。

### 6.3 防止参数欺骗

如果接口同时收到 `datasourceId` 和 `snapshotId`，不得只相信传入的 datasourceId。解析器必须读取 snapshot 的真实 datasourceId，并校验两者一致；不一致直接拒绝。

## 7. 不能交给 AOP 的权限逻辑

### 7.1 列表与分页

列表不是简单的“允许/拒绝整个接口”。Service 必须先计算当前用户在指定功能下可负责的数据源集合，并将范围下推到 SQL：

```text
visibleDatasourceIdsWithFunction(userId, functionCode)
  -> WHERE datasource_id IN (...)
  -> ORDER BY / LIMIT / OFFSET
```

禁止先从数据库分页再在 Java 内过滤，否则会出现总数错误、空页和越过分页边界的可见性问题。无可见范围时返回空页；如果接口语义要求至少一个负责源，则由 Service 明确拒绝。

### 7.2 批量操作

批量操作必须：

1. 限制单批数量；
2. 一次查询全部对象与真实归属；
3. 去重并校验每个对象；
4. 任意对象无权则整批拒绝；
5. 全部通过后才在同一事务执行；
6. 审计中记录安全摘要，不记录秘密值或业务原值。

### 7.3 领域规则

以下规则继续留在 Service：

- 不能审批本人；
- 审批批准范围必须是申请范围的子集；
- 临时授权必须有期限且不能超过上限；
- 受保护系统管理员、最后管理员绑定和本人负责源保护；
- ALLOW/DENY、部门继承、有效期和字段保护合并；
- 治理状态、发布快照、废弃/阻断资源校验；
- 查询任务归属、revision 复查、SQL/导出能力、最终脱敏；
- 创建资源后如何建立负责源关系等事务内规则。

## 8. 特殊接口处理

### 8.1 工作台

入口使用 `admin:workbench:view` 全局功能。每张卡片继续由 Service 按所属域功能和负责源分别裁剪；入口注解不能代表用户自动拥有所有卡片的数据。

### 8.2 无现成资源 ID 的创建接口

例如创建数据源、测试尚未保存的连接：

- 入口校验对应全局管理功能；
- 创建成功后是否建立负责源关系，按 B0 冻结规则在同一事务处理；
- 不能因为拥有创建功能就推导业务数据查询授权。

### 8.3 列表、统计和导出

- 列表与统计必须按负责源在查询层裁剪；
- 详情必须重新解析目标资源，不得因为从列表进入就跳过校验；
- 导出除查看范围外还要检查独立导出功能；
- 批量、统计、导出和直接 API 与页面入口使用相同的 S1 强制点。

### 8.4 S1 安全问数

`/api/iam-s1/query` 保留 B2/B3 专用实现。通用后台鉴权切面不能代替：

- 数据授权 Resolver；
- Context Firewall；
- RAG 表列过滤；
- SQL AST 校验与记录条件注入；
- permission revision；
- Java 最终脱敏与当前权限复查。

## 9. 建议包结构

```text
com.dataocean.module.permission.s1
  annotation/
    IamS1Global.java
    IamS1Resource.java
  aspect/
    IamS1AuthorizationAspect.java
  resource/
    IamS1ResourceType.java
    IamS1ResolvedResource.java
    IamS1ResourceResolver.java
    IamS1ResourceResolverRegistry.java
    impl/
      DatasourceResourceResolver.java
      SnapshotResourceResolver.java
      MetadataEntityResourceResolver.java
      KnowledgeDocumentResourceResolver.java
      QueryTaskResourceResolver.java
      AuditLogResourceResolver.java
  support/
    IamS1AdminGuard.java
    IamS1ReasonMessages.java
```

解析器只能依赖最小只读 Mapper/查询 Service，避免反向依赖 Controller 或触发带副作用的业务 Service。

## 10. B4-A 实施步骤

### A1：框架骨架

- 新增两个注解、资源类型、解析器接口和注册表；
- 新增授权切面，底层只调用现有 Guard/Resolver；
- 定义切面与事务、审计切面的顺序；
- 未知功能、未知资源类型、空参数和解析异常全部默认拒绝。

### A2：用现有两个批次验证

- 工作台入口迁移到全局功能注解；
- 数据源详情、编辑、删除、启停、已保存连接测试迁移到资源注解；
- 数据源列表继续由 Service 做可见范围 SQL 下推；
- 创建数据源和未保存连接测试使用全局管理功能；
- 删除 Controller 中等价的重复 Guard 调用，保留 Service 范围和业务校验。

### A3：资源解析器

按 B4 批次顺序实现：

1. datasource；
2. snapshot、metadata entity/column；
3. governance issue/rule 所属资源；
4. knowledge/glossary/prompt 所属范围；
5. audit、lineage、system operation resource。

### A4：接口覆盖测试

自动扫描 Controller，要求：

- 所有纳入 B0 消费矩阵的直接 API 都有 S1 注解或进入显式例外清单；
- 例外清单只能包含登录、健康检查、明确的 S1 专用查询链等已说明入口；
- 已迁移接口不存在旧 `@PreAuthorize`；
- 功能码存在且全局/资源类型与 B0 一致；
- 写接口不能只声明查看功能；
- 同一路径不能同时命中新旧权限实现；
- Controller 新增方法未声明权限时测试失败。

### A5：继续六域批次

B4 批次 3～6 必须在框架和覆盖测试就绪后继续。每个批次同时完成：

- Controller 动作准入注解；
- 资源解析器；
- Service 列表/统计范围下推；
- 详情、批量、导出和写操作校验；
- 前端路由、Tab、按钮能力显示；
- 单元测试和接口覆盖测试。

## 11. 测试要求

### 11.1 切面测试

- 未登录返回 401；
- 未授予功能返回 403；
- 功能和负责源位于不同角色绑定时拒绝；
- 同一启用绑定同时拥有功能和负责源时通过；
- 禁用用户、角色、绑定或数据源时拒绝；
- 资源不存在、参数为空、解析异常时拒绝；
- 切面只读取 S1 事实，不读取旧 JWT authority 和旧权限表。

### 11.2 资源解析测试

- 每种资源正确解析 datasourceId；
- 路径 datasourceId 与真实归属不一致时拒绝；
- 跨数据源伪造 ID 时拒绝；
- 批量包含一个无权对象时整批拒绝；
- 禁用、断链和未知归属默认拒绝。

### 11.3 列表与响应测试

- SQL 查询包含可见 datasourceId 范围；
- 空范围返回空页且总数正确；
- 分页、排序、统计不在过滤前执行；
- 详情、导出、批量接口不能绕过列表范围；
- 前端隐藏按钮之外，直接调用 API 同样被拒绝。

### 11.4 架构测试

- 固定 54 码无未知引用；
- B0 消费矩阵中的接口无遗漏；
- 已迁移 Controller 无旧权限注解；
- S1 切面和 Resolver 生产代码无旧权限依赖；
- 不允许同一请求路径同时调用旧、新授权算法。

## 11.5 实施状态（2026-09-20）

### 已实现

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| `@IamS1Global` | `permission/s1/annotation` | 全局功能准入，只允许用于 B0 冻结为「全」的功能 |
| `@IamS1Resource` | 同上 | 资源功能准入，`resourceIds` 支持单值与多值，逐个解析校验 |
| `@IamS1ScopedList` | 同上 | 源范围功能的**功能级准入**：用于列表/搜索/统计入口，以及无现成资源 ID 的创建/探测入口 |
| `IamS1AuthorizationAspect` | `permission/s1/aspect` | 三种注解的准入切面；`@Order(HIGHEST_PRECEDENCE + 10)`，在事务切面之前 |
| `IamS1ResourceType` / `IamS1ResolvedResource` / `IamS1ResourceResolver` / `IamS1ResourceResolverRegistry` | `permission/s1/resource` | 固定注册表；一种类型有且只有一个解析器；未注册类型 fail-closed |
| `DatasourceResourceResolver` | `resource/impl` | datasourceId → 未删除的数据源身份 |
| `SnapshotResourceResolver` | 同上 | snapshotId → datasourceId（归属断链拒绝） |
| `MetadataEntityResourceResolver` | 同上 | entityId → `entity_metadata.datasource_id` |
| `MetadataColumnResourceResolver` | 同上 | 列实体 → snapshot / datasource / table（非 COLUMN 实体拒绝） |

`IamS1FunctionCatalog` 增加 `FunctionScope`（`GLOBAL` / `RESOURCE` / `MIXED`）与 `scopeOf(code)`：切面据此校验「注解语义与 B0 冻结一致」，把源范围功能标成全局功能会直接拒绝执行。`glossary:*` 三个「源/全」混合码标为 `MIXED`，注解框架拒绝使用，待批次 5 定稿后再开放。

受限 SpEL 用 `SimpleEvaluationContext.forReadOnlyDataBinding()`：禁止 Bean 引用、类型引用、构造对象与任意方法调用；表达式只提取资源 ID，真实归属一律由解析器重新查询。

### 已迁移（批次 1～3，共 41 个端点）

| Controller | 端点数 |
| --- | --- |
| `DashboardController` | 1 |
| `DatasourceAdminController` | 11 |
| `MetadataCatalogController` | 12 |
| `MetadataCollectionController` | 8 |
| `SnapshotVersionController` | 9 |

迁移只删除与注解等价的准入调用；列表范围下推、批量逐项校验、双侧快照校验、血缘可见性裁剪、事务锁与业务规则全部保留在 Service。

### 覆盖扫描

`IamS1EndpointCoverageTest` 用 `RequestMappingHandlerMapping` 枚举真实 HandlerMethod，断言：已迁移 Controller 每个方法恰好一个 S1 注解、无旧 `@PreAuthorize`、功能码来自固定目录且全局/资源语义一致、写接口不能只声明查看功能、未迁移 Controller 必须显式登记、已迁移 Controller 不得出现在例外清单。

例外清单**粒度**：已迁移范围精确到 Handler 方法；未迁移部分按 Controller + 计划批次登记（31 个 Controller / 174 个端点）。每迁移一批就移出对应 Controller，移出后立即接受精确到方法的检查。例外不使用路径前缀匹配。

### 例外清单粒度（门禁要求）

例外清单**逐端点**冻结在 `IamS1EndpointExemptions`：键为 `HTTP 方法 + 完整路径`，值为 `Controller#Handler方法|原因`，共 **174** 条。每迁移一个端点删除一条、不新增条目；新增未注解 Handler 会立即失败。测试另校验清单不存在过期条目。禁止改成路径前缀或 Controller 级豁免——那会让该 Controller 新增的未注解方法自动通过。

### 完整 confirm() 事务集成测试

`MetadataMaskCandidateConfirmTransactionTest`（4 个用例）走真实 `MetadataMaskCandidateService` + 真实 Mapper + 真实 Spring 事务，覆盖：写入唯一 ACTIVE 保护并清除候选、候选已清除后再次确认幂等、**两个并发 confirm 最终只保留一条 ACTIVE**、候选不可清理时不产生新事实。

**H2 边界（必须如实说明）**：H2 能验证 Spring 事务与 Mapper 配合、行锁串行化和幂等结果，但**不能替代 B5 在真实 MySQL REPEATABLE READ 下的验收**。

### 尚未完成

- 权限与组织域的 22 个 `IamS1*` 端点仍是显式 Guard，未迁到注解框架（已逐端点登记在例外清单）。
- `glossary:*` 三个「源/全」混合码的最终语义必须在进入批次 5 前确定，当前注解框架拒绝使用。

## 12. B4-A 完成标准

以下条件全部满足，才算 B4-A 完成并允许继续全面扩散：

- 注解、切面、Resolver 注册表和首批资源解析器已实现；
- 工作台、数据源两个已完成批次迁移并验证；
- 列表范围仍在 Service/SQL 层正确裁剪；
- 接口覆盖扫描能够发现未声明权限的新接口；
- 旧权限事实未进入 S1 判断；
- 聚焦测试、完整 Java 测试和 `git diff --check` 通过；
- 代码完成复审并提交到 B4 分支；
- 未执行真实 migration、bootstrap、部署或正式切换。

## 13. B4 到 B5 的最终门禁

只有同时满足以下条件才能进入 B5：

1. B4-A 完成；
2. 六个后台域全部接入 S1；
3. 134 个端点以 B0 实际消费矩阵复核，无遗漏、无新旧混用；
4. 路由、工作区、Tab、按钮和直接 API 的功能码一致；
5. 列表、详情、批量、统计、导出均按负责源强制；
6. B4 剩余复审问题关闭；
7. Java、Python、前端测试与构建通过；
8. B4 代码已提交、推送并停止继续增加架构改动；
9. migration 顺序、V53 编号处理、备份、回退和维护窗口方案已确认。

B5 才执行真实 migration、bootstrap、初始化新角色/负责源/数据授权、服务启动、接口与浏览器验收和正式切换。B5 失败时只能在切换前回退并继续运行旧体系，不能让一次授权判断同时读取新旧权限。


## 11.6 批次 4（数据治理域）实施补充

- 新增资源类型 `GOVERNANCE_ISSUE` 与 `GovernanceIssueResourceResolver`：归属以**快照真实 datasourceId**为准并校验与问题行一致。**问题行的 `datasource_id` 为空同样 409**——该列自 V11 起就是 `NOT NULL`，为空是事实缺失而不是「以快照为准」的合法历史形态。
- **同一个功能码可以有两种范围**：`governance:rule:manage` 既覆盖全局规则启停（无 datasourceId，Service 强制系统管理员），也覆盖表/列治理状态（功能 + 负责源）。注解只表达动作准入，这种差异必须留在 Service，不能把功能码整体改成系统管理员专属。
- 列表范围用**集合参数**表达（`listIssuesInDatasources`），空集合即空页；禁止用 `null` 兼表“全局”。
- **指定快照必须直接判归属**：`?snapshotId=` 不能只塞进 `WHERE`——那会让无权快照返回空页，把「无权」伪装成「没有数据」，并把无权资源变成可探测目标。顺序是快照不存在 404 → 归属断链 409 → 不在传入的可见数据源集合内 403 → 通过后才分页查询。
- 批量处理的原子边界要写准确：**权限与归属预校验整批原子**（任一无权/不存在/断链即整批拒绝且零修改）；**状态流转本身允许部分成功**并如实返回 `skipped`。不要笼统描述成「整批业务原子」；若要真正的全有或全无，必须先验证所有状态流转再统一修改，不能捕获异常继续。
- 分派责任人只写工作归属，**不产生任何数据授权**；写入前必须校验账号存在且启用。

### 11.6.1 切面必须声明 `@Aspect`（2026-09-20 复审发现的 P0）

`IamS1AuthorizationAspect` 一度只有 `@Component` 没有 `@Aspect`。Spring AOP 不会把这样的类当成切面，
`@Before` 通知一条都不执行；而接入本框架时对应的旧 `@PreAuthorize` 已被删除，`/api/admin/**` 在
`SecurityConfig` 里又只要求 `authenticated()`——**已迁移端点当时对任何已登录用户开放**，
而全部既有测试仍然是绿色。

因此：

- 切面类必须同时声明 `@Aspect` 与 `@Component`，二者缺一不可；
- 只断言「注解存在」的测试不足以覆盖这条链路，必须有**代理层**的断言：
  `IamS1EndpointCoverageTest#migratedControllersAreActuallyProxiedSoTheAnnotationsRun` 在真实容器里
  断言每个已迁移 Controller 都拿到了 AOP 代理，并核对确实扫到了全部已迁移 Controller；
- `MetadataGovernanceControllerAuthorizationTest` 用 `AspectJProxyFactory` 代理真实 Controller，
  断言「权限拒绝时 Service 零调用」；
- 切面生效后，`@IamS1Resource` 的 SpEL 表达式会真正求值：参数名写错会变成运行时恒 403。
  `IamS1EndpointCoverageTest#resourceExpressionsReferenceRealParameters` 按真实参数名静态核对根变量。

## 11.7 批次 5（语义中心）实施补充

批次 5 迁移 `GlossaryController` 14 + `KnowledgeDocController` 18 + `PromptTemplateController` 10 =
**42 个方法级端点**（以 `RequestMappingHandlerMapping` 实际枚举为准），例外清单 162 → **120**。

> 复核说明：本轮任务书按「Glossary 14 + Knowledge 19 + Prompt 10 = 43」给出基线。实际枚举为 42——
> 任务书 Knowledge 清单的第 19 项是「复核 Controller 实际映射，确保没有漏项或重复计数」这条**说明**，
> 不是端点。`KnowledgeDocController` 真实映射就是 18 个方法（8 个 GET、9 个 POST、1 个 PUT），
> 与例外清单删除条数一致，**没有漏项**。

### 11.7.1 `glossary:*` MIXED 语义冻结（本轮定稿）

`glossary:view` / `glossary:manage` / `glossary:approve` **保持 `FunctionScope.MIXED`**，
不改成 `GLOBAL` 或 `RESOURCE`。冻结规则：

| 场景 | 规则 |
| --- | --- |
| 术语/术语表**未关联任何数据源** | 三个码都**只校验功能**；不因为“未绑定”推导任何数据源权限 |
| **查看**（已关联） | 只返回调用者在 `glossary:view` 下负责源内的关联字段；未绑定术语继续按全局语义显示；已绑定术语**至少有一个可见关联源**时才显示基本信息；无任何可见关联源时**不返回**该术语；不得泄露无权源的字段名、FQN、实体 ID 或关联关系 |
| **写**（修改/删除/提交审核/退回草稿/审核，已关联） | 解析术语当前关联的**全部** datasourceId，每个源都要求同一绑定上的对应功能 + 负责源；**任意一个无权则整体拒绝**；未关联源时按全局功能判断 |
| **关联新字段** | 校验术语当前全部关联源 → 解析目标 `entityId` 的**真实** datasourceId → 再校验目标源；任一无权整体拒绝；**不采信前端传入的数据源** |
| **解除字段** | 校验术语当前全部关联源 → 校验被解除实体的真实 datasourceId；任一无权整体拒绝 |
| **术语表更新/删除** | 汇总该术语表下全部术语关联的数据源；无关联源时按全局功能，有关联源时逐源校验；删除前继续执行现有非空、状态与关联保护 |
| **审核** | 使用独立的 `glossary:approve`；**不自动拥有** `glossary:manage`，**不自动拥有**业务查询权；多源术语必须逐源校验 |

**框架接入方式**：Glossary 的 14 个端点统一用 `@IamS1ScopedList` 做功能级入口准入；
`@IamS1ScopedList` 的范围校验扩展为**接受 `RESOURCE` 或 `MIXED`、仍拒绝 `GLOBAL`**；
`@IamS1Global` 与 `@IamS1Resource` 继续**拒绝 `MIXED`**（它们要求唯一确定的范围语义，
会把动态范围固化成一个错误结论）。

MIXED 的动态范围由 `GlossaryScopeService` 落实，授权判定复用 `IamS1AdminGuard` /
`IamS1CapabilityService`，**不复制任何授权 SQL**：

- `termDatasourceIds(termId)` / `termDatasourceIdsByTerm(Collection)` —— 一次关系查询
  （`GLOSSARY_OF` 关系行）+ 一次实体批量查询，查询次数与术语数量无关（无 N+1）；
- `glossaryDatasourceIds(glossaryId)` —— 术语表下全部术语关联源的并集；
- `entityDatasourceIds(Collection)` —— 实体 → 真实数据源（从 `entity_metadata.datasource_id` 解析）；
- `requireFunctionOnSources(userId, code, datasourceIds)` —— 空集合只校验功能；非空逐个校验、
  任一无权立即 403（排序后校验，行为确定）；
- `GlossaryScopeService.visibleIn(termSources, visibleDatasources)` —— 纯函数可见性规则
  （未绑定 → 可见；已绑定 → 至少一个可见源），便于列表先算一次可见集合再逐条判定。

术语表（`glossary`）自身不携带数据源归属，因此列表只返回术语表基本信息（名称、描述、状态），
不含任何字段名、FQN、实体 ID 或关联关系；作用域规则作用在**术语**与**关联字段**上。

### 11.7.2 Knowledge 端点功能码与资源范围

| 功能码 | 端点 |
| --- | --- |
| `knowledge:view` | 列表、详情、审核记录(`review-tasks`)、来源快照(`source-snapshots`)、版本列表、版本详情、版本差异、索引任务(`vector-tasks`)、切分预览(`preview-chunks`) |
| `knowledge:manage` | 新建、编辑、提交审核(`submit-review`)、生成草稿(`generate-draft`)、按快照批量生成(`generate-from-snapshot`) |
| `knowledge:approve` | 审核通过、驳回 |
| `knowledge:publish` | 发布、回滚 |

**只读 POST 例外**：`POST /api/admin/knowledge-docs/{id}/preview-chunks` 虽然方法是 POST，
但按 B0 冻结为**只读预览**（模拟切片、不落库），必须使用 `knowledge:view`。
覆盖扫描按**精确的 HTTP 方法 + 完整路径**登记该例外（`READ_ONLY_POST_ENDPOINTS`），
并有 `readOnlyPostAllowlistStaysExact` 校验其只含 POST 且真实存在；
**禁止放宽成“所有 POST + view”或路径前缀匹配**。

**新增资源类型 `KNOWLEDGE_DOCUMENT`** 与 `KnowledgeDocumentResourceResolver`：

```text
documentId → knowledge_doc.datasource_id → 当前版本（current_version）
           → 版本 datasource_id / metadata_snapshot_id（如存在）
```

- 文档不存在 → **404**；
- `datasourceId` 缺失 → **409**；
- 当前版本的 `datasource_id` 与文档不一致 → **409**；
- 当前版本的来源快照缺失、归属不完整或属于其它数据源 → **409**；
- 不读取任何旧权限事实。

**列表范围**：`listDocs` 用 `@IamS1ScopedList("knowledge:view")`，
`KnowledgeDocCrudService.listDocsInDatasources` 把负责源**下推 SQL**（`WHERE datasource_id IN (...)`）；
空负责源返回空页；调用方**显式筛选**一个自己无权的 `datasourceId` 时直接 **403**——
返回空页会把「无权」伪装成「该数据源没有文档」（与批次 4 对指定无权快照的处理一致）。

**其余端点的归属复核**：文档详情/修改/生命周期操作使用 `KNOWLEDGE_DOCUMENT`（文档真实归属）；
`createDoc` 从请求 DTO 取 `datasourceId` 并由 `DATASOURCE` 解析器复查；
`generate-from-snapshot` 使用 `SNAPSHOT` 解析器，并在 Service 内**额外**校验
「请求的 datasourceId == 快照真实归属」——只校验快照等于允许
「用一个自己负责的快照 + 一个自己无权源的数据源 ID」去读别人数据源的表结构并交给 Python，
校验发生在任何读取与外部调用之前（零元数据读取、零 Python 调用、零文档写入）。

**RAG 生命周期不变**：`APPROVED → INDEXING → Python chunk/vectorize → verify → PUBLISHED →
commit 后清理旧版本`；「新版本验证成功前保留旧向量」的安全规则未被本批次触碰。

### 11.7.3 Prompt 三个功能码

`prompt:view` / `prompt:manage` / `prompt:approve` 都是 B0 冻结的**全局**功能，使用 `@IamS1Global`，
三者**互不包含**：

| 功能码 | 端点 |
| --- | --- |
| `prompt:view` | 列表、效果统计(`effectiveness`)、详情、版本历史 |
| `prompt:manage` | 更新(`PUT {code}`)、提交审核、启停(`PATCH {code}/enabled`)、回滚 |
| `prompt:approve` | 审核通过、驳回 |

- 类级与全部方法级旧 `@PreAuthorize` 已删除（含原先把 view/manage/approve 混在一起的查看表达式）；
- `enabled` 原先允许 `prompt:manage` 或 `prompt:approve`，现按 B0 归入 `prompt:manage`（收紧）；
- 查看接口只返回模板内容与状态，不返回任何密钥或秘密配置；状态机与审计保持不变；
- Python 侧读取 Prompt 仍只走 `/internal/prompts/**` 的**内部服务令牌**，
  **不接入后台用户注解**，`/internal/prompts/**` 的安全边界未改动。

### 11.7.4 覆盖扫描与例外

- `MIGRATED_CONTROLLERS` 增加 `GlossaryController` / `KnowledgeDocController` / `PromptTemplateController`，
  已迁移 Controller 共 **9 个**；
- 例外清单删除批次 5 的 **42** 条：162 → **120**；
- 继续保证：每个已迁移 Handler 恰好一个 S1 方法级注解、Controller 被真实 AOP 代理、
  资源表达式引用真实参数、无旧 `@PreAuthorize`、新旧权限不混用、例外 key/value 与真实 Handler
  双向一致、新增未注解端点立即失败。

### 11.7.5 P0 回归保护（继续生效）

2026-09-20 发现的 `@Aspect` 缺失 P0 的四项保护全部保留，并在批次 5 扩展到新 Controller：

1. 切面同时声明 `@Aspect` 与 `@Component`；
2. `IamS1EndpointCoverageTest#migratedControllersAreActuallyProxiedSoTheAnnotationsRun`
   —— 真实容器中 9 个已迁移 Controller 全部拿到 AOP 代理；
3. `IamS1EndpointCoverageTest#resourceExpressionsReferenceRealParameters`
   —— 每个 `@IamS1Resource` 表达式的根变量都能在真实参数名里找到；
4. 三个新 Controller 各自的 `AspectJProxyFactory` 真实代理调用测试：
   - `GlossaryControllerAuthorizationTest`（9 个）：MIXED 范围拒绝发生在写入前、未绑定只校验功能、
     术语不存在时 404 而不是按未关联放行、审核权不带来维护权、关联同时校验术语源与目标实体源、
     目标实体归属断链 fail-closed、关联字段只返回可见源、可见性纯函数规则、未登录不进 Service；
   - `KnowledgeDocControllerAuthorizationTest`（9 个）：无权 publish/approve/rollback 零 Service 调用、
     `generate-from-snapshot` 按快照真实归属拒绝且零 Python 调用、只读 `preview-chunks` 用 view 码、
     列表范围下推、空负责源传空集合；
   - `PromptTemplateControllerAuthorizationTest`（6 个）：三码独立（approve 不等于 manage、
     manage 不等于 approve）、查看只需 view、拒绝时 Service 零调用。

以上测试全部用 `AspectJProxyFactory` 代理**真实 Controller**，
**不允许**只直接调用 `aspect.check...()` 冒充代理生效。

### 11.8 批次 5 复审修复（2026-09-20）

复审在批次 5 中发现两个 P1 与一个 P2，修复如下。

#### 11.8.1 P1：术语范围必须是**三态**，空集合不能兼表「未绑定」

**缺陷**：术语存在 `GLOSSARY_OF` 关系、但目标实体不存在 / 读取失败 / 缺少 `datasource_id` 时，
该关系不贡献数据源，术语最终得到**空集合**；而空集合的定义是「未绑定 → 全局放行」。
这会把「已绑定但归属损坏」**错误升级**成「合法未绑定」。

**修复**：`GlossaryScopeService` 返回 `Scope(status, datasourceIds)`，状态互斥：

| 状态 | 含义 | 处理 |
| --- | --- | --- |
| `UNBOUND` | 确实没有任何关联关系 | 按全局功能（不推导任何数据源权限） |
| `BOUND` | 关联完整，已解析出数据源集合 | 逐源校验 |
| `BROKEN` | **存在**关联，但实体不存在 / 读取失败 / 缺少数据源归属 | 查看列表**不返回**该术语；写、审核、删除、关联一律 **409**；术语表含 BROKEN 术语时更新/删除术语表同样 **409** |

要点：

- 术语只要**有一条**关联解析不出归属，整个术语即 `BROKEN`——不能只丢掉坏的那条、
  把剩下的当成「合法的已绑定集合」。
- 术语表的范围是其下术语的汇总：**任一**术语 `BROKEN` 即整表 `BROKEN`，
  否则「改术语表」会成为绕过该术语 409 的旁路。
- 术语不存在仍是 **404**，不会因为「查不到关联源」退化成 `UNBOUND`。
- `GET /terms/{termId}/linked-columns` 对 `BROKEN` 术语返回 **409**（返回空列表会把
  「关联已损坏」伪装成「这个术语本来就没有关联字段」，与批次 4 对无权快照的处理同一原则）。
  这是超出任务书字面要求的一处判断，如需改成「返回空列表」请明确。

#### 11.8.2 P1：知识文档的**历史版本链**归属保护

**缺陷**：文档级注解只解析文档与**当前版本**，但版本列表 / 版本详情 / 版本差异 / 审核记录 /
来源快照 / 回滚 / 索引任务都会读取**历史版本**。`KnowledgeVersionServiceImpl` 原先直接按
`docId` 返回版本，不校验每个版本的 `datasource_id` 与 `metadata_snapshot_id`。
其中 **rollback** 会把归属错误的历史版本内容重新写入并创建向量化任务。

**修复**：新增 `KnowledgeOwnershipValidator`（`module/knowledge/support`），
所有版本、快照与向量任务的读取/写入共用同一个校验点：

```text
版本 datasourceId 必须存在且 == doc.datasourceId          → 否则 409
版本的 metadataSnapshotId 若存在：快照必须存在、
  归属完整、且 == doc.datasourceId                        → 否则 409
向量任务的 datasourceId 必须 == doc.datasourceId          → 否则 409
```

接入位置：

| 位置 | 说明 |
| --- | --- |
| `listVersions` | 校验返回的**全部**版本（版本列表 / 审核记录 / 来源快照都由它派生） |
| `getVersion` | 校验该版本；**版本差异与回滚都经过它** |
| `createVersion` | 写入前校验来源快照归属（生成草稿的 `snapshotId` 由客户端指定） |
| `listVectorTasksOfDocument` | 新增的文档级读取入口，校验每个任务的数据源 |
| `KnowledgeDocumentResourceResolver` | 改为复用同一校验器 |

Document Resolver 同时修正为：

- `currentVersion > 0` 但**版本记录不存在** → 409；
- 当前版本 `datasourceId` 为空 → 409；
- `currentVersion` 为空或 `≤ 0` 表示历史「尚无版本」，**显式允许并有测试钉住**，
  不是「任意缺失都放行」。

`metadataSnapshotId` 为空仍是**合法**状态（手工创建的文档没有来源快照）。

#### 11.8.3 P2：术语只能关联**物理列**实体

`link-column` 原先接受任何带数据源归属的 `MetadataEntity`（表、库、数据源本身都能建
`GLOSSARY_OF`），会稀释「术语 → 字段」的语义，也让按列做的字段级判定失去前提。
现强制 `MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())`，否则返回 **400**。
解除关联不再重复该校验（历史脏数据需要可修复路径），但**仍然**校验术语现有源与被解除实体的真实源。

### 11.9 批次 6 资源边界（2026-09-21）

批次 6 把组织基础、运营与平台、字段治理、血缘写入迁到注解后，复审发现注解准入
**不等于**资源范围。以下规则已落实，验收不得再把「Controller 被 AOP 代理」当成范围正确。

#### 11.9.1 P0：血缘写入必须校验关系两端

`LINEAGE_RELATIONSHIP` 解析器只返回**源实体**的 datasource，供删除接口做动作准入。
Service 必须在写入前收集全部 `source` / `target` / 列映射实体，逐源调用
`IamS1AdminGuard.requireDatasourceFunction(..., lineage:manage, datasourceId)`。
`batchCreateLineage` 限制 200 条，任一无权、不存在或断链即整批零写入。

#### 11.9.2 P0：字段标签批量与 CSV 必须先校验再写

`governance:field:manage` 在批量接口上只能做功能级准入。写入前由
`FieldGovernanceScopeSupport.requireColumnsWritable` 限制数量、批量读取
`db_column_meta` 真实归属并逐源校验。CSV 必须先完整解析再调用该方法，禁止边读边写。
CSV 同时限制最多 200 行和文件大小 1MB。

#### 11.9.3 P1：字段治理列表必须 SQL 下推

`@IamS1ScopedList` 不能裁剪数据。`by-tag`、可信度分页/批量、待审反馈必须把负责源
推进 SQL（或先把可见列 ID 推进 `IN`）。空负责源返回空页；调用方显式传入无权
`datasourceId` 返回 403，不能伪装成空结果。`batchGetConfidence` 对显式字段 ID
不能静默过滤：任一无权或不存在即整批 403/404，并限制最多 200 个。
字段与查询任务同时存在但 datasource 不一致时，`FEEDBACK_REVIEW` 解析器返回 409。

#### 11.9.4 P1：一个请求只命中一套权限实现

`LineageController` 使用 S1 注解后，`LineageServiceImpl` **不得**再调用旧
`DatasourceAccessService`。可见性裁剪留在血缘 Service；旧授权不能成为新授权的前置条件。

#### 11.9.5 P1：新用户管理不得操作旧角色事实

`organization:user:manage` 只管理账号与部门。非空旧 `roleIds` 返回 400；创建用户
不写 `sys_user_role`。S1 角色绑定只走 `/api/iam-s1/users/{id}/roles`。列表/详情的
角色字段保持为空。删除/禁用必须保护最后一个仍可登录的受保护系统管理员绑定，
不能只拦固定 `id=1`。

删除用户必须在同一事务内：锁定该用户 S1 绑定 → 最后管理员保护 → 将全部
ACTIVE `iam_s1_user_role` 改为 DISABLED → 将直接 USER grant 改为 REVOKED
（行保留为历史事实）→ 记录 S1 revision 与审计 → 再逻辑删除用户。
**不得**再把旧 `PermissionChangedEvent` 当作新权限失效机制。

#### 11.9.6 P1：组织事实变化必须递增 S1 revision

S1 Resolver 的授权输入包含用户状态、主部门、部门启停和部门路径。
`UserServiceImpl` / `DepartmentServiceImpl` 在同一事务内必须调用
`IamS1PermissionRevisionService.record`：用户主部门变化、禁用/锁定/删除/重新启用；
部门创建、改名、改父级、启停、删除。事务回滚时 revision 与业务修改一起回滚。
部门删除同样把该部门 ACTIVE grant 标为 REVOKED。

#### 11.9.7 P1：正式组织入口不得混用旧角色权限

B4 保留旧 `RoleController` / `PermissionController`，但旧页面
`/admin/access/organization` 只作为切换前入口（旧角色 + 权限项）。
正式入口 `/admin/access/iam-organization` 只使用 S1 用户/部门能力与
`/api/iam-s1/**`。B5 切换时必须移除旧导航和旧路由。正式 S1 页面不得引用
`listRoles` / `listPermissions` / `updateRolePermissions` / `assignRoleToUser`。
