# DataOcean 后台重构状态与整改计划

> 文档状态：唯一有效入口
>
> 更新日期：2026-09-21
>
> 适用范围：后台前端重构、轨道 A 验收、后续整改、导航信息架构和后续开发顺序

## 1. 使用规则

本文是以下信息的唯一有效来源：

- 后台重构当前完成度；
- 轨道 A 验收结论与证据；
- 当前必须修复的事项和复验标准；
- 后台一级、二级、三级导航的最终规则；
- 轨道 B、轨道 C 和其他未完成工作。

代码实现、单元测试、浏览器验收和最终通过是四个不同状态，不得互相替代。以后只在本文更新当前状态；`后续开发.md` 仅维护下一步执行顺序。历史过程通过 Git history、截图和机器证据追溯，不再创建新的“状态看板”“阶段总结”或重复验收说明。

复验的原始输出、截图和机器结果直接保存到 `output/playwright/`；不再为后台重构创建平行的叙述性状态报告，最终结论只回写本文。

## 2. 当前结论

**轨道 A 已完成。**

2026-09-13 在真实 MySQL、Redis、Milvus、Java、Python 和 Chromium 桌面环境完成复验。A-01～A-10 的运行时修复、正式导航、权限基线、真实治理/知识/问数流程和浏览器验收均通过；主动注入的局部 500 仅作为错误态验证并单独记录。

当前唯一主线是：

```text
轨道 A 通过并冻结侧栏两级信息架构
-> 进入轨道 B 权限体系重构
```

后台前端重构已通过真实桌面浏览器验收；截图、构建和单元测试分别作为独立证据保留。

## 3. 当前状态

| 项目 | 当前结果 | 是否阻断轨道 A |
| --- | --- | --- |
| 本机 MySQL、Redis、Milvus、Java、Python、前端启动 | 真实启动并连通 | 否 |
| Java 单元测试 | B4 最新基线 **557** 通过；B5 准备轮复跑为 **563** 通过、0 失败、0 错误 | 否 |
| 前端 Vitest | B4 最新基线 **11 文件 67** 通过 | 否 |
| 前端生产构建 | `vue-tsc` 与 Vite 通过 | 否 |
| Python 单元测试 | 204 通过、4 跳过、1 警告（B4 记录） | 否 |
| Flyway | 真实 MySQL **仍停留 V50**；V51/V52/V54/V55/V56/V57 未执行 | 否 |
| 旧后台 URL 兼容层 | 已删除；32 个旧 URL 统一 NotFound/404 | 否 |
| 浏览器截图 | 当前证据目录 92 张桌面截图，无失败截图 | 否 |
| 数据源创建、测试连接和采集 | 真实业务库成功采集最新快照 | 否 |
| 知识生成、审核、发布和索引 | 真实生成 3 个业务域文档，审核、向量任务和发布均完成 | 否 |
| 质量检查与问题闭环 | 真实检查、分派、处理、复查和批量部分成功通过 | 否 |
| Prompt 启停 | `chart_generation` 停用/重新启用均返回 200 | 否 |
| 查询审计、操作日志等管理接口 | 真实 `*` 超级管理员可访问 | 否 |
| 问数页 URL 上下文 | 两个真实数据源 URL/返回/前进后退一致 | 否 |
| “猜你想问”全链路 | 任务落库、VO、渲染、点击回填和刷新恢复通过 | 否 |
| 桌面端导航 | 7 个一级域、20 个正式工作区均在左侧，键盘/折叠通过 | 否 |
| 浏览器自动化 | 45 页面、35 断言、16 个 API、32 个旧路由，129 条结果全部通过；未解释 console/page/request 错误均为 0 | 否 |
| 轨道 B-B0 权限清单 | B0-1.1 已评审通过；已冻结 54 个目标码消费矩阵、IAM-SIMPLE-1 独立新表方案、新契约、启动式首个新系统管理员 bootstrap 和 17 项 B6 删除/保留清单 | 否 |
| 轨道 B-B1 新配置基础 | B1 已提交并推送：提交 `e373c8152b095048ab8a3d7ea7b571e188f29b9a`；V54 独立 S1 表/固定 54 码/角色关系/负责源关系/修订审计/启动式 bootstrap 基础；聚焦测试 34 个、完整 Java 测试 188 个通过。已补 SpringFactoriesLoader 注册、完整 Environment bootstrap Web 隔离、调度隔离、敏感功能配置与后台角色转授边界、管理员并发锁、失效 bootstrap 拒绝、稳定 revision target 和失败审计。未做真实数据库升级、服务/浏览器验收或真实 bootstrap | 否 |
| 轨道 B-B2 数据授权与统一计算 | B2 代码已提交到本地（包含在 `6eb5427`）：V55 独立建立数据授权、明确字段、结构化记录条件和字段保护；统一 Resolver/实际权限预览和 Redis revision 快照缓存保留。B2 基线聚焦 111 个、完整 Java 265 个通过。未执行 V55 真实数据库升级 | 否 |
| 轨道 B-B3 安全查询链路 | B3 已提交到本地（`d9a0c3b`）：V56 前向 migration、独立 `/api/iam-s1/query` 与 `/internal/iam-s1/*`、严格 S1 合同、Context Firewall、真实 Milvus 检索后权限过滤、全字段 SQL AST、参数化行条件、来源追踪、Java 最终保护、事务提交后调度和任务/历史/SQL/CSV/反馈/SSE 当前权限复查。已修复直接 execute 绕过、RIGHT JOIN 保留侧、空来源落库、连字符 binding、危险函数/LIMIT/深度缺口。B3.1（P1）：外层行数封顶不再被子查询/派生表/UNION 分支 LIMIT 阻断（`limit_rule` 检查全部 LIMIT 节点）、字段 usage 的 PROJECTION/FILTER/JOIN 在 AST 层强制且星号只展开可投影字段、SSE 复用 `get` 同一读取路径并按当前权限再脱敏；另修复失败/取消任务被误报「权限已变化」并统一 Java/Python usage 取值集合。B3.2（阻断）：表别名/CTE/派生表按作用域解析、禁止跨库限定表名、记录条件只注入到持有该表的作用域且逗号/交叉连接落 WHERE、`query:use` 改为读取时复查。B3.3（P0 脱敏绕过）：集合运算按列位置汇总全部分支来源，修复后续分支脱敏字段漏标导致的最终脱敏绕过。B3.4（P1 保护一致性）：同一输出列汇入多种非空脱敏策略时拒绝，集合运算分支列数改为双向校验。B3.5（P1 规范化）：脱敏冲突判定按小写规范化（与 Java `maskResultByFields` 口径一致，Java 侧 8 处 `toLowerCase` 改用 `Locale.ROOT`），集合运算分支的常量/无来源输出在 AST 阶段直接拒绝。B3.6（P1 最终保护）：Java `deriveOutputMasks` 独立按小写列名聚合策略并在冲突时 fail-closed（完成阶段拒绝落库、任务/历史读取阶段拒绝返回）。B3.1～B3.6 与配置忽略提交（`d08d37f`）已随 `70d4999` 提交到本地，分支本地提交尚未推送。测试结果：Java `IamS1*Test` 122 个、完整 276 个通过（B3 提交时记录值）；Python B3 聚焦 40 个、完整 204 passed/4 skipped/1 warning。未执行 V55/V56 真实数据库升级、服务/浏览器验收、真实 bootstrap 或 B5 切换 | 否 |
| 轨道 B-B4 页面和业务接入 | **B4 批次 1～6 代码与自动化验证已完成并合入 `1f0a5f4`（含 `1a6e426`）**，不是“未提交的工作树草稿”。B4-A 注解框架（`@IamS1Global` / `@IamS1Resource` / `@IamS1ScopedList` + `@Aspect` `@Component` 切面）已落地；已迁移 Controller **23** 个；批次 6 实际 **61** 个 Handler；精确例外 **77** 条。B4 最新自动化基线：Java **557**、前端 Vitest **67**。旧 Role/Permission/DatasourcePermission/AccessPolicy/AccessApproval **不迁移**，保留到 B6；正式旧组织导航 `/admin/access/organization` 在 **B5 切换时移除**。权限与组织域 22 个 `IamS1*` 端点仍是显式 Guard。测试 profile 关闭 Flyway，自动化全绿不能证明 V51～V57 已在真实 MySQL 执行。**B4 没有真实服务或浏览器验收。B5 尚未执行。真实数据库仍为 V50。** B5 手册已补 SQL 门禁、`mysqldump --result-file` 备份、禁止覆盖式导入和隔离恢复演练。**不要批准 `17f2b96` 为最终 B5 SHA。** | 否 |

### 3.1 已通过且需要保留的结果

- DRAFT 知识文档禁止回滚；
- 术语 `APPROVED -> DRAFT` 退回；
- 非 `security:manage` 用户只能看到自己的访问申请；
- 数据源切换可清理旧 `snapshotId`，已验证的后台工作区可继承上下文；
- 问题批量处理的部分成功提示、责任人分派和工作区唯一高亮；
- 知识详情六个 Tab：内容、来源与覆盖、审核记录、版本、切分预览、索引状态；
- 当前正式路由的页面与权限回归；旧后台 URL 只验证统一 NotFound/404，不再做兼容验收；
- V52 的 `query_task.suggested_questions` 为 JSON 字段。

历史验收项 F7/F8/F9 已从轨道 A 浏览器验收范围移除：F7 仅保留后端 readiness 的防御性 `UNKNOWN` 兜底，F8 的旧 `known=false` 前端路径和 F9 的历史 `SIGN'` 异常路径均已不存在。三者都不是当前缺陷，也不阻塞轨道 B。

## 4. 导航最终决策

后台当前仅以桌面端为设计、实现和验收范围。

### 4.1 层级位置

后台采用三级结构，但**一级和二级都必须放在左侧侧栏**：

```text
一级：左侧侧栏的业务域
二级：一级业务域下展开的工作区
三级：内容区中的页面 Tab、详情页、抽屉、弹窗和具体动作
```

内容区顶部不再放置全局二级工作区导航。`AdminWorkspaceNav.vue` 已删除，二级工作区由 `AdminDomainNav.vue` 在侧栏内渲染。

### 4.2 一级业务域与二级工作区

| 一级业务域 | 二级工作区及目标路径 |
| --- | --- |
| 工作台 | 无固定二级入口；`/admin/workbench` |
| 数据接入 | 数据源 `/admin/data-sources`；采集任务 `/admin/collections` |
| 数据资产 | 资产目录 `/admin/assets`；版本发布 `/admin/releases` |
| 数据治理 | 治理总览 `/admin/governance`；问题中心 `/admin/governance/issues`；规则与状态 `/admin/governance/rules`；字段治理 `/admin/governance/fields` |
| 语义中心 | 业务术语 `/admin/semantics/glossaries`；语义知识 `/admin/semantics/knowledge`；Prompt 策略 `/admin/semantics/prompts` |
| 权限与组织 | 授权管理 `/admin/access`；访问审批 `/admin/access/approvals`；组织与角色 `/admin/access/organization` |
| 运营与平台 | 查询分析 `/admin/operations/queries`；数据血缘 `/admin/operations/lineage`；运行监控 `/admin/platform/runtime`；操作日志 `/admin/platform/operation-logs`；AI 配置 `/admin/platform/ai` |

### 4.3 桌面端交互

- 侧栏展开时，一级业务域始终可见；当前业务域在其下直接显示二级工作区。
- 点击一级业务域时展开该域，并进入该域第一个可访问的二级工作区；工作台直接进入工作台。
- 当前一级和当前二级必须同时有清晰、唯一的选中状态。
- 默认只展开当前业务域，避免七个业务域全部展开后形成超长菜单。
- 一级侧栏折叠为图标态时，点击图标应展开侧栏或显示包含二级工作区的可键盘操作浮层；不能让二级入口消失。
- 页面标题区只保留页面标题、对象摘要、全局操作、通知和用户入口，不再重复工作区导航。

### 4.4 路由和上下文

- 保留 `domainKey`、`workspaceKey`、`contextMode` 作为路由归属和高亮的唯一依据。
- 二级入口继续按目标工作区的 `contextMode` 继承 `datasourceId`、`snapshotId`。
- 不复制目标页面不认识的 `tab`、分页或筛选参数。
- 详情页、版本差异、审核记录等对象页面不新增二级菜单，通过所属工作区高亮和对象摘要表达位置。
- 页面内部 Tab 属于第三级，必须使用 URL 参数恢复刷新、前进和后退状态。

### 4.5 导航验收标准

- 桌面展开和桌面折叠状态均能到达全部合法工作区。
- 一级、二级不会同时错误高亮多个项目。
- 直接 URL、刷新、前进、后退后高亮与页面一致。
- 键盘可以进入侧栏、展开一级业务域并激活二级工作区；焦点样式可见。
- `aria-current`、展开状态和控件名称可被辅助技术识别。
- 旧后台 URL 不再重定向到正式页面，统一进入 NotFound 或返回 404。

## 5. 轨道 A 必须修改的事项

### A-01 修复 MyBatis-Plus 乐观锁运行时错误

**现象**：知识提交审核返回 500，异常包含 `MP_OPTLOCK_VERSION_ORIGINAL not found`；Prompt 启停也在带 `@Version` 的实体上执行 `updateById` 并返回 500。

**修改要求**：

1. 在 `MyBatisPlusConfig` 的 `MybatisPlusInterceptor` 中注册 `OptimisticLockerInnerInterceptor`，并保留分页拦截器。
2. 复核 `KnowledgeDoc`、`PromptTemplate` 以及所有带 `@Version` 实体的 `updateById` 路径。
3. 更新影响行数为 0 时返回明确的 409 冲突，不得统一变成 500。
4. 补充真实 MySQL 集成测试，不能只使用 Mockito 模拟 Mapper 返回值。

MyBatis-Plus 官方规则要求 `@Version` 与 `OptimisticLockerInnerInterceptor` 配套；整数版本在成功更新后自动加一并回写实体。参考：[MyBatis-Plus Optimistic Lock Plugin](https://baomidou.com/en/plugins/optimistic-locker/)。

**复验**：知识提交审核、通过、拒绝、发布前置流转和 Prompt 启停连续执行均成功；并发旧版本更新得到明确冲突。

### A-02 为 Java 调 Python 的内部请求统一携带令牌

**现象**：`/internal/knowledge/generate-draft` 因缺少 `X-Internal-Token` 返回 400。

**修改要求**：

1. 从已有 `dataocean.internal.token` 配置读取令牌。
2. 在统一的 Python `RestClient` 构造处添加 `X-Internal-Token` 默认请求头，避免各客户端重复拼装。
3. 确认知识、RAG、查询摘要、SQL、图表、配置刷新和连接池接口均使用一致的内部认证策略。
4. 测试和日志不得输出令牌值。

**复验**：Java 发起真实知识草稿生成成功；缺少或错误令牌仍被 Python 拒绝。

### A-03 查明并修复质量检查 500

**现象**：`POST /api/admin/snapshots/{id}/quality-check` 返回 500，现有验收材料没有保存完整根因。

**修改要求**：

1. 重新运行并保存完整 Java 异常链；
2. 判断是数据源连接、解密、SQL、规则数据、事务还是状态更新失败；
3. 修复后为预期业务条件返回 4xx 和中文提示，非预期异常保留可追踪日志；
4. 增加服务测试及真实业务库集成验证。

**复验**：真实快照可以生成质量问题，问题列表、`assigneeId` 筛选、处理和复查形成连续闭环。

### A-04 统一超级管理员 `*` 权限语义

**现象**：审计和操作日志 Controller 只检查 `audit:view`，真实 `*` 超级管理员返回 403；其他 Controller 已显式允许 `*`，行为不一致。

**修改要求**：

1. 在轨道 B 新权限模型落地前，所有应对超级管理员开放的接口统一支持 `*`。
2. 优先收敛为统一授权表达式或授权服务，避免每个 Controller 自行遗漏。
3. 覆盖查询审计、慢查询、操作日志、告警规则和血缘相关接口。

**复验**：真实 `*` 管理员可访问全部约定工作区；普通用户仍按权限点被拒绝。

### A-05 修复问数页 URL 数据源恢复

**现象**：直接打开 `/query?datasourceId=1`，页面实际选择数据源 2。

**修改要求**：

1. 初始化时先读取并校验 `route.query.datasourceId`，合法且可访问时优先使用。
2. URL 数据源不可访问或不存在时显示明确提示，再选择安全回退，不得静默切换。
3. 用户切换数据源时同步更新 URL。
4. 保证会话、消息、readiness 和结果严格按数据源隔离。

**复验**：对两个数据源分别执行直接 URL、刷新、前进、后退、切换和历史会话恢复，页面对象与 URL 始终一致。

### A-06 修复 Python 失败测试

**现象**：`TestSqlExecutor.test_execute_failure_increments_retry` 失败，重试后 `execution_result.error` 为空。

**修改要求**：恢复执行失败错误传播，保证重试计数和最终错误信息同时存在；补充成功重试、重试耗尽和取消场景。

**复验**：Python 全套测试通过，允许保留已有明确原因的 skip。

### A-07 完成“猜你想问”全链路

必须验证：

```text
Python suggestedQuestions 产出
-> Java SSE 解析
-> query_task.suggested_questions 落库
-> QueryTaskVO 返回
-> 前端渲染并可点击回填
-> 刷新历史消息后恢复
```

不得只以 V52 字段存在作为通过依据。

### A-08 把二级导航迁入侧栏

**涉及位置**：

- `frontend/src/components/admin/AdminShell.vue`
- `frontend/src/components/admin/AdminDomainNav.vue`
- `frontend/src/components/admin/AdminWorkspaceNav.vue`
- `frontend/src/router/adminNavigation.ts`
- `frontend/src/router/index.ts`

**修改要求**：按第 4 节完成桌面展开和折叠状态；删除内容区顶部的二级工作区条，不改变现有 URL 与路由元数据语义。

### A-09 修复可见体验问题

- 治理问题描述存在中文乱码，需确认夹具编码、数据库连接字符集和前端解码链路；
- 工作台局部失败直接显示英文 `Request failed with status code 500`，应转换为中文业务提示；
- 二级导航迁入侧栏后，页面内部 Tab 与导航应有明显层级差异；

### A-10 补齐未完成的验收覆盖

- F1 的可达 readiness 状态逐项验证，并保留后端状态枚举边界说明；
- 连接池重置二次确认；
- 告警规则区不展示伪造统计；
- 页面加载、空状态、局部失败、无权限和重试行为；
- 桌面展开态和折叠态的导航可达性；
- 真实数据和真实 `*` 超级管理员完整主流程。

## 6. 轨道 A 重新验收顺序

### 6.0 最终复验结果（2026-09-13）

本次复验按真实流程执行并通过：采集快照 10、质量检查和问题处理、快照审核发布、知识文档 27/28/29 生成/审核/索引/发布、Prompt 停用/启用、真实 NL2SQL 查询和会话推荐问题恢复。浏览器以 1440×1000 桌面视口从登录开始执行；最终证据记录 45 个页面、35 个断言、16 个 API 和 32 个旧路由结果，共 129 条结果且全部通过。F7 仅作为后端 readiness 的防御性 `UNKNOWN` 兜底保留，不属于浏览器验收项；F8、F9 对应的旧代码路径已经不存在。console error、page error、request failure 均无未解释项。

### 6.1 自动化基线

1. `frontend`: `npm run build`
2. `backend/DataOcean`: `mvn test`
3. `python-service`: `uv run pytest`

三项都必须通过；构建通过不替代浏览器验收。

### 6.2 后端真实接口

1. 知识草稿生成、提交审核、审核、发布、向量任务和回滚；
2. Prompt 启停；
3. 质量检查、问题生成、分派、处理和复查；
4. 审计、慢查询、操作日志和告警规则权限；
5. 访问审批范围；
6. “猜你想问”落库与读取。

### 6.3 浏览器主流程

1. 登录和强制改密；
2. 数据源创建、测试连接、采集、质量检查、问题处理；
3. 快照审核发布；
4. 知识生成、审核、索引和发布；
5. 进入问数并执行真实查询；
6. 查看表格、图表、猜你想问、历史会话恢复；
7. 权限、审批、审计、运行监控和异常体验；
8. 新侧栏两级导航的桌面展开态、折叠态、直接 URL 和键盘操作；
9. 旧后台 URL 统一 NotFound/404 回归。

### 6.4 通过条件

以下条件全部满足后，才能把轨道 A 改为完成：

- 自动化测试全部通过；
- 核心业务流程无 500/意外 400/错误 403；
- 数据源上下文没有串源；
- 两级侧栏导航在桌面展开态和折叠态均可达；
- 所有必验项有真实响应或浏览器证据；
- 浏览器证据中的所有结果均通过；
- 本文状态更新为“轨道 A 已通过”，并记录复验日期和代码提交。

## 7. 轨道 A 之后的工作

### 7.1 轨道 B：权限体系重构

轨道 A 和导航结构已冻结。目标输入为 `docs/development/guides/DataOcean-完整权限体系设计.md` 的简明版 1.4 / IAM-SIMPLE-1。B0～B4（含 B4-A 与批次 1～6）的代码与自动化验证已合入 `codex/iam-s1-b5-preparation` 的 `1f0a5f4`（包含 IAM 实现提交 `1a6e426`）。已迁移 Controller **23** 个；批次 6 实际 **61** 个 Handler。B4 最新自动化基线：Java **557**、前端 Vitest **67**。旧 Role/Permission/DatasourcePermission/AccessPolicy/AccessApproval 入口保留到 B6；正式旧组织导航 `/admin/access/organization` 在 B5 切换时移除。各阶段的自动化结果不代表真实数据库升级、正式运行、浏览器验收、bootstrap 或 B5 切换。**B5 尚未执行。** 真实数据库停留在 V50：V51、V52、V54、V55、V56、V57 共 6 个迁移未执行，`iam_s1*` 表在真实库中不存在。V53 永久不再使用。B5 手册见 `docs/development/guides/DataOcean-IAM-S1-B5切换与回退手册.md`。

用户要求权限简单易懂，采用“角色管功能、部门默认数据与个人/角色授权管可查数据、后台角色绑定负责源”的模型。管理员首屏使用中文名称、作用与例子；查询/SQL/导出功能只在角色配置，数据授权不重复开关，不建设多层委派或数字优先级。

替换严格按“建立并验收新权限 → 切换且真实复验通过 → 删除旧权限”。不做旧授权映射、回填、双读双写或兼容兜底；切换前保留旧体系运行，新体系独立配置验收。旧角色/权限/策略、旧 JWT 与旧缓存不得输入新 Resolver，同名权限码不继承旧关系。账号、密码、真实组织、业务资产、会话与审计保留；新角色关系/数据授权重新初始化，首个新系统管理员通过一次性受控流程显式绑定。B4 批次 1～6 的代码与自动化验证已完成并合入 `1f0a5f4`；B4 没有真实服务或浏览器验收。B5 准备轮已写出切换/回退手册、真实库只读 SQL 门禁与只读 preflight；备份回退已改为 `mysqldump --result-file`、禁止覆盖式导入、并要求隔离恢复演练。**不要批准 `17f2b96` 为最终 B5 SHA。** 尚未执行真实 migration、bootstrap、部署或正式切换。未提供 bootstrap userId、最终 SHA、维护窗口、备份位置与校验和、初始化角色/负责源/授权清单之前，不得开始真实 B5。

### 7.2 轨道 C：功能待办

按以下顺序评估：

1. Agent workflow 测试补强；
2. RAG 真实 Milvus 验证和 Hit@K、MRR、nDCG 基线；
3. 文档/版本级 RAG 全量重建编排，不得先删除活动向量；
4. P9 告警评估：新增定时评估任务，按启用规则计算错误率和慢查询数，调用 `NotificationService`，同一规则恢复前只通知一次；告警历史表使用 **V58 或更高未占用版本**。**V53 永久不再使用**，不为填编号创建空 migration，V54～V57 执行后禁止再新增 V53；
5. P12 告警历史前端：依赖 P9，复用现有规则 CRUD，在“运营与平台”的侧栏二级工作区内提供历史查询，不新增一级入口。

### 7.3 仍未完成的质量项

严重：

- `SnapshotLifecycleServiceImpl` 查询审核人姓名时静默吞异常；
- `QueryController.submitFeedback` 使用原始 `Map<String, String>`，应改 DTO + `@Valid`。

中等：

- `DataQualityChecker` 的 SQL 异常仅用 debug 日志；
- `pool_manager.py` 用配置池大小代替实际活跃连接数；
- DashScope base URL 硬编码；
- 验证码参数硬编码；
- `ColumnCollector` 动态列名查询仍需进一步收敛；
- `PromptInternalController` 保留不安全默认令牌字面量，非 dev 启动校验已降低风险。

待清理：

- `QualityScoreAggregationService` 应改为通过 `MetadataSnapshotMapper`/JOIN 过滤。

待产品确认但不阻断轨道 A：

- 问题中心“分配给我的”当前是显式筛选而非默认开启。建议保持默认关闭，避免管理员首次进入得到空列表；如需体现优先级，应由后端按“分配给当前用户、高危、阻断”排序，而不是强制过滤掉其他问题。

## 8. 现有验收证据

- 浏览器脚本：`output/playwright/轨道A浏览器验收.cjs`
- 最终机器证据：`output/playwright/轨道A浏览器验收证据.json`
- 截图目录：`output/playwright/`（最终复验为桌面视口）
- 主业务库夹具：`docs/review/fixtures/轨道A-业务库.sql`
- 辅助数据源夹具：`docs/review/fixtures/轨道A-辅助数据源.sql`

最终证据对应真实接口与浏览器复验；早期失败过程仍可通过 Git history 和运行日志追溯，不作为当前结论。

## 9. 文档维护约束

- 本文只保留当前状态和未完成事项，完成项压缩到“已通过结果”。
- 设计决策发生变化时先更新本文，再改代码。
- 每次复验只在本文更新最终结论；原始日志和截图作为证据文件保存。
- `README.md`、`AGENTS.md`、`CLAUDE.md` 和前端协作说明统一链接本文。
- 任何新文档不得自称“唯一状态入口”或复制本文的进度表。

### 9.1 本次已合并并删除的文档

以下文档中的有效现状、整改、导航和待办信息已收口到本文，原文件已删除；历史内容仍可通过 Git 恢复：

- `docs/development/项目真实状态看板.md`
- `docs/development/completed/后台信息架构与导航规范.md`
- `docs/development/guides/DataOcean-后台前端整体重构任务与实施指导.md`
- `docs/development/guides/DataOcean-后台前端整体重构开发指导.md`
- `docs/development/guides/DataOcean-后台前端整体重构实施任务清单.md`
- `docs/development/stage-summaries/阶段5-实施总结.md`
- `docs/development/stage-summaries/阶段6-实施总结.md`
- `docs/development/stage-summaries/阶段7-实施总结.md`
- `docs/development/stage-summaries/阶段8-实施总结.md`
- `docs/development/stage-summaries/原能力迁移对照表.md`
- `docs/review/2026-09-11-后台前端重构审查-前端缺陷清单.md`
- `docs/review/2026-09-11-后台前端重构审查-后端事实问题.md`
- `docs/review/2026-09-12-后台前端重构阶段5-8审查报告.md`
- `docs/review/2026-09-13-轨道A验收报告.md`
