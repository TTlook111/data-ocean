# DataOcean 后台前端整体重构开发指导

> 文档状态：当前开发基线  
> 适用范围：后台前端整体重构，不修改 Java、Python、数据库、权限码和后端 API URL  
> 配套执行清单：`DataOcean-后台前端整体重构实施任务清单.md`

## 1. 文档结论

DataOcean 后台前端应按照完整业务流程重新设计，不再以现有菜单、页面组件和前端 URL 为约束。

本轮允许大规模调整或重写前端，包括：

- 后台壳层和导航。
- 一级菜单和二级工作区。
- 页面布局和交互方式。
- 前端路由和 URL。
- 列表、详情、审核、版本等页面的组织方式。
- 全局上下文和页面状态管理。
- 现有 Vue 组件的保留、拆分、合并或替换。

本轮不修改 Java、Python、数据库和现有权限码。后端公共 API、业务状态机和安全校验是新前端的事实基础。

后台重构完成后，再根据最终业务域、工作区和用户任务规划新的权限系统。

## 2. 重构目标

新后台必须让用户清楚回答五个问题：

1. 我当前管理的是哪个业务对象？
2. 它现在处于什么状态？
3. 为什么不能继续？
4. 我现在能够执行什么操作？
5. 完成当前操作后，下一步去哪里？

最终使用体验应从“功能页面集合”转变为“任务驱动的业务工作台”。

### 2.1 核心业务旅程

```text
创建数据源
→ 验证连接
→ 启用数据源
→ 采集元数据
→ 查看采集任务和快照
→ 执行质量检查
→ 处理治理问题
→ 审核并发布快照
→ 生成语义知识
→ 编辑并提交审核
→ 审核、索引并发布知识
→ 配置访问授权和细粒度策略
→ 检查可问数就绪度
→ 进入智能问数
→ 查看查询审计、反馈、性能和血缘
→ 将反馈重新用于治理和语义优化
```

菜单用于定位业务域；工作区用于完成用户任务；对象详情用于承载连续操作；状态卡片和行动按钮用于引导下一步。

## 3. 后端能力盘点结论

本节基于当前 Java Controller、Service、DTO、VO 和状态实现整理。后续开发必须再次以实际代码为准，不得只参考当前前端页面。

### 3.1 工作台与就绪度

| 能力 | API | 当前事实 | 新前端用途 |
| --- | --- | --- | --- |
| 后台统计 | `GET /api/admin/dashboard/stats` | 统计用户、数据源、快照、表字段、治理问题和近期活动；当前仅 `*` 权限 | 超级管理员工作台概览 |
| 单数据源就绪度 | `GET /api/admin/datasources/{id}/readiness` | 返回阶段、进度、五个就绪维度、已发布快照/知识版本和阻断原因 | 数据源详情的流程驾驶舱 |
| 批量就绪度 | `GET /api/admin/datasources/readiness/batch` | 可批量返回多个数据源的就绪状态 | 工作台数据源准备情况列表 |
| 用户侧就绪度 | `GET /api/datasources/{id}/readiness` | 按当前用户权限计算是否可问数 | 智能问数入口判断 |
| 通知 | `GET /api/notifications` 等 | 支持列表、未读数、单条/批量已读 | 顶部通知和工作台待处理提醒 |

就绪度模型已提供：

- `connectionReady`
- `metadataReady`
- `governanceReady`
- `knowledgeReady`
- `permissionReady`
- `askable`
- `stage`、`stageLabel`、`progress`
- `blockReasons.code`
- `blockReasons.message`
- `blockReasons.ownerRole`
- `blockReasons.actionText`
- `blockReasons.actionPath`

新前端必须复用这些事实，不能在前端重新拼装另一套就绪状态。

### 3.2 数据源与连接

基础路径：`/api/admin/datasources`

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `GET` | `/simple` | 获取允许当前后台角色使用的数据源简表 |
| `GET` | `/` | 分页查询数据源 |
| `GET` | `/{id}` | 查看数据源详情 |
| `POST` | `/` | 创建数据源 |
| `PUT` | `/{id}` | 编辑数据源 |
| `DELETE` | `/{id}` | 删除数据源 |
| `PATCH` | `/{id}/status` | 启用或停用数据源 |
| `POST` | `/test-connection` | 使用未保存配置测试连接 |
| `POST` | `/{id}/test-connection` | 测试已保存的数据源连接 |
| `GET` | `/{id}/readiness` | 查看上线和问数就绪度 |
| `GET` | `/readiness/batch` | 批量查看就绪度 |
| `POST` | `/{id}/access` | 旧数据源用户授权入口 |
| `GET` | `/{id}/access` | 查看旧授权列表 |
| `DELETE` | `/{id}/access/{userId}` | 撤销旧用户授权 |

新前端应把数据源创建、连接测试、启用状态和就绪度组织为一个连续流程，不再要求用户在列表、详情和多个业务域之间自行判断下一步。

### 3.3 元数据采集与同步

基础路径：`/api/admin/metadata`

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `POST` | `/sync` | 发起全量元数据同步，返回任务 ID |
| `GET` | `/sync-tasks` | 按数据源分页查看同步任务 |
| `GET` | `/snapshots` | 按数据源分页查看快照 |
| `GET` | `/snapshots/{id}` | 查看快照详情 |
| `GET` | `/snapshots/{id}/tables` | 查看快照中的表 |
| `GET` | `/snapshots/{id}/tables/{tableName}/columns` | 查看表字段 |
| `GET` | `/snapshots/diff` | 比较两个快照 |
| `POST` | `/snapshots/diff/record` | 比较并记录变更事件 |

同步调度：

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `GET` | `/api/admin/system/sync-schedule` | 获取 Cron、启用状态和运行状态 |
| `PUT` | `/api/admin/system/sync-schedule` | 更新同步调度 |

同步任务状态为 `PENDING → RUNNING → SUCCESS / FAILED`。页面应展示任务进度、失败原因和重新发起入口，不能只展示状态码。

### 3.4 数据目录、实体和关系

基础路径：`/api/admin/catalog`

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `GET` | `/search` | 按关键词和实体类型搜索全局目录；参数虽接收 `datasourceId`，当前实现未实际应用该过滤 |
| `GET` | `/entities` | 查看指定数据源的实体 |
| `GET` | `/entities/{entityId}` | 查看实体详情和关系信息 |
| `GET` | `/entities/{entityId}/lineage` | 查看实体血缘 |
| `GET` | `/entities/{columnId}/column-lineage` | 查看字段级血缘 |
| `GET` | `/entities/{entityId}/downstream` | 查看下游影响 |
| `GET` | `/entities/{entityId}/tags` | 查看实体标签 |
| `POST` | `/entities/{entityId}/confirm-tag` | 确认实体标签 |
| `DELETE` | `/entities/{entityId}/unconfirm-tag/{tagFqn}` | 取消确认标签 |
| `GET` | `/mask-candidates` | 查看待确认脱敏候选字段 |
| `POST` | `/mask-candidates/{entityId}/confirm` | 确认脱敏策略 |
| `POST` | `/mask-candidates/{entityId}/reject` | 拒绝脱敏候选 |

血缘关系维护另有：

- `POST /api/admin/catalog/lineage`
- `DELETE /api/admin/catalog/lineage/{relationshipId}`
- `POST /api/admin/catalog/lineage/batch`

目录实体图谱只在快照发布后同步，并在新快照发布时替换该数据源的旧实体。因此“资产目录”代表当前正式发布资产，不是任意历史快照浏览器。

当前 `/search` 不能用于保证数据源隔离的目录搜索。本轮不修改后端时，资产目录应先使用 `/entities?datasourceId=...` 获取当前数据源实体，再在前端完成关键词和类型过滤；如果数据规模导致该方案不可接受，应将“目录搜索支持真实 `datasourceId` 过滤和分页”记录为后端缺口，不能直接展示可能跨数据源的搜索结果。全局搜索入口可以使用 `/search`，但必须明确其搜索范围为全部已发布资产。

> **2026-09-12 更新**：`/search` 的 `datasourceId` 过滤已下推到 SQL 并真实生效（见 §12.1），
> 数据源内搜索可直接使用 `/search?datasourceId=...`。现有页面尚未切换，仍沿用上面的
> `/entities` + 前端过滤方案，切换属阶段 8 收敛项。

数据目录不应只是搜索框。它应成为浏览数据源、表、字段、关系、标签、质量、血缘和权限摘要的统一资产入口。

### 3.5 快照检查、审核和发布

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `PATCH` | `/api/admin/snapshots/{snapshotId}/status` | 修改快照状态 |
| `POST` | `/api/admin/snapshots/{snapshotId}/publish` | 发布已批准快照 |
| `POST` | `/api/admin/snapshots/{snapshotId}/revoke` | 撤回已发布快照，必须填写原因 |
| `GET` | `/api/admin/datasources/{datasourceId}/version-history` | 查看数据源版本历史 |
| `GET` | `/api/admin/version-history` | 查看全局版本历史 |
| `GET` | `/api/admin/datasources/{datasourceId}/published-snapshot` | 获取当前发布快照 |
| `GET` | `/api/admin/snapshots/{snapshotId}/audit-logs` | 查看快照审计记录 |
| `GET` | `/api/admin/datasources/{datasourceId}/audit-logs` | 查看数据源版本操作记录 |
| `GET` | `/api/admin/snapshots/{snapshotId}/diff/{compareSnapshotId}` | 比较版本 |

快照状态机为：

```text
DRAFT
→ CHECKING
→ ISSUE_FOUND 或 APPROVED
→ PUBLISHED
→ EXPIRED
```

补充允许流转：

- `ISSUE_FOUND → APPROVED`
- `PUBLISHED → APPROVED`，用于撤回。
- 发布新快照时，旧的已发布快照自动变为 `EXPIRED`。

只有 `APPROVED` 快照可以发布。新前端必须根据状态展示允许动作，不能让用户点击后再依赖后端报错理解流程。

### 3.6 数据治理

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| `POST` | `/api/admin/snapshots/{snapshotId}/quality-check` | 对快照执行质量检查 |
| `GET` | `/api/admin/quality-rules` | 查看质量规则 |
| `PATCH` | `/api/admin/quality-rules/{ruleId}` | 启停质量规则 |
| `GET` | `/api/admin/snapshots/{snapshotId}/quality-issues` | 查看指定快照问题 |
| `GET` | `/api/admin/quality-issues` | 全局筛选治理问题 |
| `PATCH` | `/api/admin/quality-issues/{issueId}/status` | 处理单个问题 |
| `PATCH` | `/api/admin/quality-issues/batch-status` | 批量处理问题 |
| `POST` | `/api/admin/quality-issues/{issueId}/assign` | 分派问题 |
| `PATCH` | `/api/admin/snapshots/{snapshotId}/tables/{tableName}/governance-status` | 修改表治理状态 |
| `PATCH` | `/api/admin/snapshots/{snapshotId}/columns/{columnId}/governance-status` | 修改字段治理状态 |
| `PATCH` | `/api/admin/snapshots/{snapshotId}/tables/{tableName}/batch-governance-status` | 批量修改字段状态 |
| `GET` | `/api/admin/snapshots/{snapshotId}/review-records` | 查看治理审核记录 |

治理问题状态包括：

```text
OPEN
CONFIRMED
RESOLVED
REJECTED
REOPENED
AUTO_CLOSED
```

`OPEN`、`CONFIRMED`、`REOPENED` 的高危问题会阻断可问数就绪。问题中心必须突出阻断问题、责任人和处理动作。

### 3.7 字段标签、可信度和反馈

| 能力 | API |
| --- | --- |
| 字段打标 | `POST /api/field-tags` |
| 批量打标 | `POST /api/field-tags/batch` |
| 移除标签 | `DELETE /api/field-tags/{id}` |
| 查看字段标签 | `GET /api/field-tags/column/{columnMetaId}` |
| 按标签找字段 | `GET /api/field-tags/by-tag/{tagCode}` |
| 旧预定义标签列表 | `GET /api/field-tags/predefined` |
| 自动打标 | `POST /api/admin/fields/auto-tag` |
| 导入标签 | `POST /api/admin/fields/import-tags` |
| 可信度列表 | `GET /api/field-confidence` |
| 可信度详情 | `GET /api/field-confidence/{columnMetaId}` |
| 批量可信度 | `GET /api/field-confidence/batch` |
| 人工设置信心分 | `PUT /api/field-confidence/{columnMetaId}` |
| 可信度事件 | `GET /api/field-confidence/{columnMetaId}/events` |
| 可信度趋势 | `GET /api/admin/fields/{fieldId}/confidence-trend` |
| 待审核反馈 | `GET /api/feedback-reviews` |
| 批准反馈 | `POST /api/feedback-reviews/{feedbackId}/approve` |
| 拒绝反馈 | `POST /api/feedback-reviews/{feedbackId}/reject` |

当前未发现独立的分类体系 CRUD Controller。新前端不能把数据库中存在分类表等同于已经具备完整分类管理 API。字段治理工作区应优先承载当前可用的标签、可信度、反馈和脱敏候选能力；分类体系管理应标记为接口缺口，待确认后再实现。

### 3.8 业务术语

基础路径：`/api/admin/glossary`

当前支持：

- 术语表查询、创建、修改和删除。
- 术语查询、创建、修改和删除。
- 术语提交审核。
- 术语批准或拒绝。
- 术语与字段实体绑定、解绑和查询。

术语状态为：

```text
DRAFT / REJECTED
→ PENDING_REVIEW
→ APPROVED 或 REJECTED
```

业务术语页面应围绕“术语表 → 术语 → 关联字段 → 审核状态”组织，而不是把所有对象放在同一个无层级表格中。

### 3.9 语义知识

基础路径：`/api/admin/knowledge-docs`

当前支持：

- 文档列表和详情。
- 新建、编辑文档。
- 提交审核、批准、拒绝。
- 发布并进入索引任务。
- 根据快照生成单份草稿。
- 根据快照批量生成领域文档。
- 版本列表和版本详情。
- 版本差异比较。
- 版本回滚。
- 切分预览。

知识文档状态机为：

```text
DRAFT
→ PENDING_REVIEW
→ APPROVED
→ INDEXING
→ PUBLISHED
```

审核拒绝后回到 `DRAFT`。发布失败时可能恢复到 `APPROVED`；清理旧向量失败时可能进入后台补偿任务。新前端必须把“审核通过”和“完成索引并发布”显示为不同阶段。

### 3.10 Prompt 策略

基础路径：`/api/admin/prompt-templates`

当前支持：

- 模板列表、详情和效果统计。
- 编辑模板并创建新版本。
- 提交审核。
- 审核通过或拒绝。
- 版本历史。
- 回滚。

状态机为：

```text
DRAFT
→ PENDING_REVIEW
→ APPROVED 或 REJECTED
```

Prompt 管理应采用“模板列表 → 模板详情”的结构，详情中组织内容、审核、版本和效果，不能把编辑和审核动作挤在列表行中。

### 3.11 访问授权和策略

数据源级授权：`/api/admin/datasource-access`

- 创建、修改、撤销数据源授权。
- 按数据源和主体类型查询授权。
- 计算指定用户的最终权限结果。

细粒度策略：`/api/admin/access-policies`

- 创建单条或批量策略。
- 修改、删除策略。
- 按数据源、主体、表查询策略。
- 支持表、列、行过滤、脱敏、允许和拒绝语义。

新前端应把数据源授权和表列策略放在同一个“授权管理”工作区中，但保持两层概念：

```text
第一层：用户、角色或部门是否能够访问数据源
第二层：允许访问哪些表、字段，是否存在行过滤和脱敏
```

最终权限预览必须是独立能力，使用 `/decision` 接口显示计算结果和来源，避免管理员只看到规则列表却不知道最终效果。

### 3.12 访问审批

基础路径：`/api/admin/access-approvals`

当前后端已支持：

- 用户提交访问申请。
- 管理员审批通过或拒绝。
- 审批通过后生成有有效期的临时 `ALLOW` 策略。
- 到期后自动过期并清理临时策略。
- 审批时再次检查 `BLOCKED`、`DEPRECATED` 表和字段，不能绕过治理限制。

当前前端没有完整页面承载该流程。

重要边界：当前审批列表接口没有方法级审批权限限制，也没有在 Service 中按申请人自动收窄列表。新后台的管理员审批队列只能对 `security:manage` 用户显示；但前端隐藏不能替代后端安全控制。正式向普通用户开放“我的申请”前，应把列表范围和权限列为后端安全缺口，不得仅依赖前端完成隔离。

### 3.13 用户、角色、部门和权限项

当前支持：

- 用户列表、详情、创建、编辑、状态、删除和密码重置。
- 用户 CSV 模板、导入和导出。
- 角色创建、编辑、删除。
- 角色权限分配。
- 角色成员查看、添加和移除。
- 部门树查询、创建、编辑和删除。
- 权限项列表、树、创建、编辑和删除。

新前端应将这些能力放入“组织与角色”工作区，通过 Tab 或左右分栏组织，但每个 Tab 继续使用当前独立权限码控制。

### 3.14 查询审计、性能和血缘

| 能力 | API | 设计说明 |
| --- | --- | --- |
| 查询审计列表 | `GET /api/admin/audit-logs` | 支持筛选和分页 |
| 查询审计详情 | `GET /api/admin/audit-logs/{id}` | 使用 Drawer 或详情页 |
| 慢查询 | `GET /api/admin/audit-logs/slow-queries` | 当前只支持分页，不支持数据源筛选 |
| 查询统计 | `GET /api/admin/audit-logs/stats` | 支持数据源和时间范围 |
| ~~提升为模板~~ | ~~`POST /api/admin/audit-logs/{id}/promote-template`~~ | **2026-09-12 撤下**：后端实现只有一条 `log.info`，没有模板落库。端点与前端两个入口均已移除（见 `docs/review/2026-09-12-后台前端重构阶段5-8审查报告.md` A4）。此能力不再作为验收项 |
| 表血缘 | `GET /api/lineage/table/{tableName}` | 要求数据源 ID |
| 字段血缘 | `GET /api/lineage/column/{tableName}/{columnName}` | 要求数据源 ID |
| 影响分析 | `GET /api/lineage/impact/{tableName}/{columnName}` | 要求数据源 ID |

性能分析页不能显示无效的数据源选择器。若未来需要按数据源筛选慢查询，应新增后端参数后再开放该上下文。

### 3.15 运行监控、告警和平台配置

当前支持：

- 系统健康：`GET /api/admin/system/health`。
- SQL 连接池：`GET /api/admin/system/sql-pools`。
- 重置指定数据源连接池：`POST /api/admin/system/sql-pools/{datasourceId}/reset`。
- 操作日志多条件查询：`GET /api/admin/operation-logs`。
- AI 配置、供应商、模型测试、模型同步和向量维度检测。（~~重新向量化~~ 2026-09-12 撤下：Python 侧是只回显请求的占位实现，端点与前端入口均已移除，见审查报告 A5）
- 告警规则列表、创建、修改和启停。

告警当前只有规则 CRUD 和启停接口，未发现告警执行记录、告警历史、恢复、确认和去重闭环。新前端可以在运行监控中提供“告警规则”配置区，但必须明确这是规则配置，不能呈现虚假的告警中心或历史统计。

## 4. 目标信息架构

新后台采用七个一级业务域。该结构是本轮开发的冻结基线，不受当前 V1 菜单限制。实施中不得仅为减少改动恢复旧菜单；如实际接口或业务状态机证明该结构不可行，应先更新本文并记录依据，再调整实现。

```text
DataOcean 后台
│
├── 工作台
│
├── 数据接入
│   ├── 数据源
│   └── 采集任务
│
├── 数据资产
│   ├── 资产目录
│   └── 版本发布
│
├── 数据治理
│   ├── 治理总览
│   ├── 问题中心
│   ├── 规则与状态
│   └── 字段治理
│
├── 语义中心
│   ├── 业务术语
│   ├── 语义知识
│   └── Prompt 策略
│
├── 权限与组织
│   ├── 授权管理
│   ├── 访问审批
│   └── 组织与角色
│
└── 运营与平台
    ├── 查询分析
    ├── 数据血缘
    ├── 运行监控
    ├── 操作日志
    └── AI 配置
```

### 4.1 为什么采用该结构

| 业务域 | 用户认知 |
| --- | --- |
| 数据接入 | 把外部数据库接入 DataOcean，并完成连接和采集 |
| 数据资产 | 查看系统已经掌握的数据，并管理快照版本 |
| 数据治理 | 处理质量、状态、标签、可信度和反馈问题 |
| 语义中心 | 将技术元数据转化为可供 AI 使用的业务语义 |
| 权限与组织 | 决定谁可以访问什么，以及由谁管理 |
| 运营与平台 | 观察查询、血缘、运行状态、操作记录和 AI 基础配置 |

一级菜单不应再直接使用 Java 模块名或数据库对象名。

## 5. 全局后台布局

### 5.1 桌面端结构

```text
┌──────────────┬────────────────────────────────────────────┐
│ 一级业务域侧栏 │ 顶栏：页面标题 / 全局搜索 / 通知 / 用户      │
│              ├────────────────────────────────────────────┤
│ 工作台        │ 二级工作区导航或对象面包屑                   │
│ 数据接入      ├────────────────────────────────────────────┤
│ 数据资产      │ 页面范围 / 对象摘要 / 状态和主要操作          │
│ 数据治理      ├────────────────────────────────────────────┤
│ 语义中心      │                                            │
│ 权限与组织    │ 核心内容区                                  │
│ 运营与平台    │                                            │
└──────────────┴────────────────────────────────────────────┘
```

### 5.2 一级侧栏

- 默认宽度建议 232 至 248 像素。
- 折叠后建议保留 64 至 72 像素。
- 一级菜单只显示业务域。
- 当前业务域必须同时通过图标、文字或可访问名称表达，不能只依赖颜色。
- “进入智能问数”作为跨产品主操作固定放在侧栏底部或工作台明显位置，不作为后台业务域之一。

### 5.3 顶栏

顶栏固定承载：

- 当前页面标题。
- 全局资产或功能搜索入口。
- 通知中心。
- 当前用户和退出入口。

顶栏不重复堆放页面筛选器。数据源、快照等范围只在页面任务需要时显示。

### 5.4 二级工作区导航

- 用于同一业务域中的稳定任务切换。
- 每个二级入口必须代表一个完整任务。
- 对象详情、审核记录、版本差异等不进入二级导航。
- 当业务域只有一个工作区时，可以不显示额外二级导航。

### 5.5 页面标题区

页面标题区统一包括：

```text
业务标题
一句话任务说明
当前状态或范围
一个主要操作
不超过两个次要操作
```

更多操作进入“更多”菜单，避免每个页面顶部出现一排同等级按钮。

## 6. 上下文与状态模型

### 6.1 上下文类型

| 页面类型 | 数据源 | 快照 | 对象 |
| --- | --- | --- | --- |
| 工作台 | 不固定 | 不固定 | 无 |
| 数据源列表 | 不显示 | 不显示 | 无 |
| 数据源详情 | 路由锁定 | 只读摘要 | 数据源 ID 锁定 |
| 采集任务 | 必选或可选筛选 | 不显示 | 任务详情由任务 ID 锁定 |
| 资产目录 | 必选 | 不显示；固定为当前已发布资产 | 实体详情由实体 ID 锁定 |
| 版本发布 | 必选 | 当前操作快照锁定或选择 | 快照 ID 锁定 |
| 数据治理 | 必选 | 必选 | 问题或字段详情按 ID 锁定 |
| 语义知识列表 | 可选筛选 | 不显示 | 无 |
| 语义知识详情 | 由文档绑定 | 来源快照只读 | 文档 ID 锁定 |
| 授权管理 | 必选 | 使用已发布快照作为资源范围 | 主体和策略按 ID 锁定 |
| 查询审计 | 可选筛选 | 不显示 | 审计记录按 ID 锁定 |
| 性能分析 | 当前不显示 | 不显示 | 无 |
| 平台配置 | 不显示 | 不显示 | 配置对象按 ID 锁定 |

### 6.2 规则

1. 路由对象优先于全局选择器。
2. 锁定对象页面不能通过全局选择器把页面切换成另一个对象。
3. 切换数据源时立即清除旧快照、旧知识文档、旧表字段和旧筛选条件。
4. 合法 URL 参数优先于本地持久化状态。
5. 资产目录固定使用当前数据源最新的已发布资产实体，不提供快照切换。
6. 没有已发布快照时显示“尚无正式资产”，并引导进入版本发布；草稿和历史快照只在版本发布及快照详情中查看。
7. URL 中的对象必须通过详情接口校验，不能依赖列表第一页是否包含该对象。
8. 页面内部不得再创建一套与全局上下文无关的数据源选择状态。

## 7. 核心页面设计

### 7.1 工作台

工作台不是统计数字堆叠页，而是当前管理员的任务入口。

建议结构：

```text
今日概览
→ 待处理事项
→ 数据源上线进度
→ 近期异常和阻断
→ 最近活动
```

主要区域：

1. 数据源总数、可问数数量、治理阻断数量、待审核知识、待审批访问。
2. 按优先级排列的待办：失败同步、高危治理问题、待发布快照、待审核知识、待审批访问、向量重建失败。
3. 每个数据源显示就绪进度、当前阶段、首个阻断原因和处理按钮。
4. 通知列表只显示与当前用户相关的最近消息。
5. 本轮 `*` 超级管理员可以看到全局统计；接口失败时不能展示假的全零数据。

### 7.2 数据源列表

主要任务：创建数据源、判断哪些数据源需要处理并进入详情。

列表列建议：

- 名称和数据库。
- 启用状态。
- 连接健康。
- 当前上线阶段。
- 可问数状态。
- 最近同步时间。
- 责任提示。
- 主要操作。

空状态主操作为“创建第一个数据源”。创建流程应在保存前支持连接测试。

### 7.3 数据源详情：上线驾驶舱

数据源详情是整个后台主流程的核心入口。

页面头部固定展示：

- 数据源名称、类型、库名和启用状态。
- 连接健康。
- 可问数状态和进度。
- 首个阻断原因。
- 当前最推荐操作。

页面采用以下 Tab：

| Tab | 内容 | 主要动作 |
| --- | --- | --- |
| 概览 | 基础信息、当前阶段、就绪进度、近期活动 | 执行下一步 |
| 连接配置 | 主机、端口、数据库、账号状态和测试结果 | 编辑、测试、启停 |
| 采集与快照 | 最近同步任务、当前快照和版本摘要 | 发起同步、查看任务、进入版本发布 |
| 可问数就绪度 | 连接、元数据、治理、知识和权限五个维度 | 跳转到阻断责任页面 |
| 活动记录 | 同步、治理、发布、权限和操作记录 | 查看详情 |

完整治理、语义和权限编辑器不复制进详情页，只展示摘要和跳转。

### 7.4 采集任务

页面结构：

```text
任务列表 | 同步调度
```

任务列表支持：

- 数据源筛选。
- 状态筛选。
- 发起同步。
- 查看任务进度和失败原因。
- 成功后进入生成的快照。
- 失败后提供重新发起入口。

同步调度作为同一工作区 Tab，不再单独占用稳定导航。

### 7.5 资产目录

页面采用“数据源范围 + 搜索/树形浏览 + 资产列表 + 详情”的结构。页面不显示快照选择器，目录内容固定对应当前发布资产。

固定结构：

```text
左侧：数据源、库、表层级或筛选
中间：搜索结果或资产列表
右侧 Drawer / 独立详情：当前资产信息
```

表详情固定组织：

```text
概览 | 字段 | 关系 | 质量 | 标签与脱敏 | 血缘 | 权限摘要
```

字段详情可以使用 Drawer，展示字段类型、描述、样例值、治理状态、标签、可信度、术语关系和上下游影响。

完整权限编辑跳到授权管理；完整治理处理跳到问题中心；全局血缘分析跳到数据血缘工作区。

### 7.6 版本发布

页面按一个数据源组织：

```text
当前发布版本
→ 待处理快照
→ 快照列表和状态
→ 版本历史
```

固定 Tab：

- `snapshots`：快照列表、质量检查、审核状态和发布入口。
- `history`：发布、撤回和版本记录。

快照差异为深层详情或 Drawer，不作为长期 Tab。

每个快照的主操作根据状态确定：

| 状态 | 主操作 |
| --- | --- |
| `DRAFT` | 执行质量检查 |
| `CHECKING` | 查看检查进度，不允许重复触发 |
| `ISSUE_FOUND` | 进入问题中心 |
| `APPROVED` | 发布快照 |
| `PUBLISHED` | 查看当前线上版本或撤回 |
| `EXPIRED` | 查看历史和差异 |

### 7.7 治理总览和问题中心

治理总览用于理解质量情况，不承载所有编辑动作。

问题中心用于处理工作：

- 默认优先展示高危、阻断和分配给当前用户的问题。
- 支持状态、严重度、维度、数据源、快照和责任人筛选。
- 问题详情展示问题对象、证据、影响、历史和允许流转。
- 处理完成后提示是否可以重新检查或返回发布流程。

### 7.8 规则与状态

将当前分散的质量规则启停和表字段治理状态维护放在一个工作区，但使用两个清晰 Tab：

```text
质量规则 | 资产状态
```

质量规则只操作当前后端已有的启停能力。资产状态按数据源和快照维护表/字段的治理状态。

### 7.9 字段治理

固定 Tab：

```text
标签 | 可信度 | 反馈审核 | 脱敏候选
```

各 Tab 共享数据源和快照上下文，但保留独立筛选参数。

分类体系管理在确认存在完整 API 前不作为正式 Tab。旧预定义标签能力应标记为兼容路径，不继续扩展为新的分类中心。

### 7.10 业务术语

建议左右分栏：

```text
术语表列表 | 当前术语表的术语列表和详情
```

术语详情展示：定义、同义词、状态、关联字段和审核记录。主操作随状态切换为编辑、提交审核或审核。

### 7.11 语义知识

列表页展示每个数据源的知识准备情况，而不只是文档标题。

知识文档详情采用：

```text
内容 | 来源与覆盖 | 审核记录 | 版本 | 切分预览 | 索引状态
```

页面头部展示生命周期步骤：

```text
草稿 → 待审核 → 已批准 → 索引中 → 已发布
```

主要操作必须按状态变化：

- `DRAFT`：编辑、生成草稿、提交审核。
- `PENDING_REVIEW`：批准或拒绝。
- `APPROVED`：发布并构建索引。
- `INDEXING`：显示进度和失败信息，禁止重复发布。
- `PUBLISHED`：查看线上版本、创建新版本或回滚。

审核队列是语义知识工作区的 Tab 或筛选视图，不再是孤立页面。

### 7.12 Prompt 策略

采用“模板列表 + 模板详情”结构。

详情 Tab：

```text
当前内容 | 审核流程 | 版本历史 | 效果统计
```

本轮 `*` 超级管理员可以看到当前状态允许的编辑和审核操作。页面不能把 `APPROVED` 简化成“已发布”，因为当前后端 Prompt 状态语义是已批准的活跃模板。

### 7.13 授权管理

采用主体驱动的操作流程：

```text
选择数据源
→ 选择主体（用户/角色/部门）
→ 查看数据源级授权
→ 查看表列范围、行过滤和脱敏策略
→ 查看最终权限结果
```

固定 Tab：

```text
数据源授权 | 表列策略 | 最终权限预览
```

`ALLOW`、`DENY`、优先级、有效期和来源必须清晰展示。写操作前再次显示目标数据源和主体，避免误授权。

### 7.14 访问审批

管理员页面固定 Tab：

```text
待我审批 | 已处理 | 已过期
```

审批详情展示申请人、数据源、表/字段、理由、申请时长、治理状态和审批后将生成的临时策略。

由于当前列表接口范围存在安全缺口，本轮前端只能将审批队列作为 `security:manage` 管理员能力。普通用户“我的申请”入口待后端补齐范围控制后再正式开放。

### 7.15 组织与角色

固定 Tab：

```text
用户 | 角色 | 部门 | 权限项
```

要求：

- 本轮四个 Tab 对 `*` 超级管理员全部可见。
- 用户详情显示所属部门、角色和状态。
- 角色详情显示权限树和成员。
- 部门采用树形结构并展示成员摘要。
- 权限项保留列表、创建、编辑和删除能力，实际请求继续由后端校验。
- 用户导入、导出和密码重置保留。

后续权限系统重构时，再为四个 Tab 和各项写操作定义独立权限；本轮不提前固化。

### 7.16 查询分析

固定 Tab：

```text
查询审计 | 性能分析
```

查询审计支持数据源筛选、统计和详情。（原「提升为模板」于 2026-09-12 撤下，理由见 §7.16 接口表。）性能分析当前只支持全局慢查询分页，因此进入该 Tab 时隐藏数据源上下文，并明确筛选能力边界。

### 7.17 数据血缘

保留为全局工作区，支持：

- 数据源选择。
- 表级和字段级血缘。
- 上下游方向和深度。
- 影响分析。
- 手工新增、删除和批量导入关系。

资产详情只展示当前对象的局部血缘，不复制完整全局工作台。

### 7.18 运行监控

固定 Tab：

```text
服务状态 | SQL 连接池 | 告警规则
```

连接池重置属于高风险操作，必须展示目标数据源并二次确认。

告警规则 Tab 明确标记当前只支持规则配置。不存在告警事件接口时，不显示虚构的告警次数、恢复率或历史趋势。

### 7.19 操作日志与 AI 配置

操作日志保留为独立工作区，因为它记录后台管理动作，与查询审计语义不同。

AI 配置保留供应商、模型、连接测试、模型同步和 Embedding 维度检测。（原「重新向量化」于 2026-09-12 撤下，理由见 §7.19 接口表。）页面只保留安全的文档版本级索引流程：发布知识文档时由后端创建版本级索引任务。若日后恢复全量重建，必须展示影响范围、当前状态和失败信息，且不作为普通保存操作的一部分。

## 8. 目标前端路由

以下为本轮冻结的目标 URL。后端 API URL 不随前端路由变化。除非开发中发现对象标识无法支撑 URL 恢复，否则不得另起一套路由命名。

| 页面 | 推荐 URL |
| --- | --- |
| 工作台 | `/admin/workbench` |
| 数据源列表 | `/admin/data-sources` |
| 数据源详情 | `/admin/data-sources/:id` |
| 采集任务 | `/admin/collections` |
| 资产目录 | `/admin/assets` |
| 资产详情 | `/admin/assets/entities/:entityId` |
| 版本发布 | `/admin/releases` |
| 快照详情 | `/admin/releases/snapshots/:snapshotId` |
| 快照差异 | `/admin/releases/snapshots/:snapshotId/diff/:compareId` |
| 治理总览 | `/admin/governance` |
| 问题中心 | `/admin/governance/issues` |
| 规则与状态 | `/admin/governance/rules` |
| 字段治理 | `/admin/governance/fields` |
| 业务术语 | `/admin/semantics/glossaries` |
| 语义知识 | `/admin/semantics/knowledge` |
| 知识文档详情 | `/admin/semantics/knowledge/:id` |
| Prompt 策略 | `/admin/semantics/prompts` |
| 授权管理 | `/admin/access` |
| 访问审批 | `/admin/access/approvals` |
| 组织与角色 | `/admin/access/organization` |
| 查询分析 | `/admin/operations/queries` |
| 数据血缘 | `/admin/operations/lineage` |
| 运行监控 | `/admin/platform/runtime` |
| 操作日志 | `/admin/platform/operation-logs` |
| AI 配置 | `/admin/platform/ai` |

需要恢复的 Tab 使用查询参数，例如：

```text
/admin/collections?tab=schedule
/admin/governance/fields?tab=feedback
/admin/semantics/knowledge?tab=review
/admin/access?tab=policies
/admin/access/organization?tab=roles
/admin/operations/queries?tab=performance
/admin/platform/runtime?tab=pools
```

## 9. 旧前端路由迁移

新页面具备原能力后，再启用以下迁移。迁移前不得删除原页面。

| 当前 URL | 目标 URL |
| --- | --- |
| `/admin` | `/admin/workbench` |
| `/admin/datasources` | `/admin/data-sources` |
| `/admin/datasources/:id/lifecycle` | `/admin/data-sources/:id` |
| `/admin/metadata/sync` | `/admin/collections` |
| `/admin/metadata/schedule` | `/admin/collections?tab=schedule` |
| `/admin/metadata/catalog` | `/admin/assets` |
| `/admin/metadata/tables` | `/admin/assets` |
| `/admin/metadata/lifecycle` | `/admin/releases` |
| `/admin/metadata/snapshots` | `/admin/releases` |
| `/admin/metadata/version-history` | `/admin/releases?tab=history` |
| `/admin/metadata/diff` | 新快照差异路由；保留原查询参数转换 |
| `/admin/governance/quality` | `/admin/governance` |
| `/admin/governance/issues` | `/admin/governance/issues` |
| `/admin/governance/status` | `/admin/governance/rules?tab=status` |
| `/admin/field/tags` | `/admin/governance/fields?tab=tags` |
| `/admin/field/confidence` | `/admin/governance/fields?tab=confidence` |
| `/admin/field/feedback-review` | `/admin/governance/fields?tab=feedback` |
| `/admin/glossary/list` | `/admin/semantics/glossaries` |
| `/admin/knowledge` | `/admin/semantics/knowledge` |
| `/admin/knowledge/editor/:id` | `/admin/semantics/knowledge/:id?tab=content` |
| `/admin/knowledge/versions/:id` | `/admin/semantics/knowledge/:id?tab=versions` |
| `/admin/knowledge/review` | `/admin/semantics/knowledge?tab=review` |
| `/admin/prompts` | `/admin/semantics/prompts` |
| `/admin/permission/access` | `/admin/access?tab=grants` |
| `/admin/permission/policies` | `/admin/access?tab=policies` |
| `/admin/users` | `/admin/access/organization?tab=users` |
| `/admin/roles` | `/admin/access/organization?tab=roles` |
| `/admin/departments` | `/admin/access/organization?tab=departments` |
| `/admin/audit/logs` | `/admin/operations/queries?tab=audit` |
| `/admin/audit/slow-queries` | `/admin/operations/queries?tab=performance` |
| `/admin/audit/data-lineage` | `/admin/operations/lineage` |
| `/admin/system/health` | `/admin/platform/runtime` |
| `/admin/system/operation-logs` | `/admin/platform/operation-logs` |
| `/admin/system/ai-config` | `/admin/platform/ai` |

旧 URL 是否长期保留由兼容需要决定。重定向至少应保留原对象 ID、Tab 和必要查询参数。

## 10. 前端组件与状态指导

### 10.1 统一通用页面结构

| 组件职责 | 基线名称 |
| --- | --- |
| 后台整体壳层 | `AdminShell` |
| 一级业务域导航 | `AdminDomainNav` |
| 二级工作区导航 | `AdminWorkspaceNav` |
| 页面标题和主要动作 | `TaskPageHeader` |
| 数据源/快照范围 | `ScopeBar` |
| 锁定对象摘要 | `ObjectContextSummary` |
| 生命周期步骤 | `LifecycleStepper` |
| 就绪度五维面板 | `ReadinessPanel` |
| 阻断原因和下一步 | `NextActionCard` |
| 状态徽标 | `BusinessStatusBadge` |
| 审计时间线 | `ActivityTimeline` |
| 统一加载状态 | `LoadingState` |
| 统一错误状态 | `ErrorState` |
| 可行动空状态 | `EmptyState` |

名称可以在首次落地时根据仓库约定微调一次，但职责必须统一，不能每个页面重复实现一套状态卡片和标题区。名称确定后应同步更新实施任务清单。

### 10.2 Store 边界

建议将状态分成：

- `auth`：当前用户和现有权限。
- `adminScope`：当前数据源和快照范围。
- `notification`：通知和未读数。
- 页面本地状态：列表筛选、分页、Drawer 和表单。

知识文档、快照、实体等详情对象由路由 ID 和页面查询管理，不进入可任意切换的全局上下文。

### 10.3 API 层

- 按新业务域重新组织 API 模块，但不修改后端 URL。
- DTO 和 VO 类型应以 Java 返回结构为准。
- 页面组件不直接散落拼接 URL。
- 同一接口只保留一个前端封装。
- 为分页、错误和状态枚举提供统一适配。

## 11. 视觉和交互规范

### 11.1 复用现有设计语言

现有项目已经使用 Element Plus、Lucide 图标和 `--do-*` 设计变量。整体重构可以更新布局，但应保持品牌连续性：

- 主色继续使用 DataOcean 蓝色体系。
- 绿色用于成功和可问数，不作为普通装饰渐变。
- 警告和阻断分别使用明确的黄、红语义。
- 使用 Lucide 图标，不新增风格不一致的图标库。
- 表格、表单、Drawer 和 Dialog 优先复用 Element Plus。

当前 `style.css` 和 `styles/variables.css` 存在重复且数值不一致的设计变量。重构时应确定唯一令牌来源，避免同名变量随导入顺序变化。

### 11.2 密度和层级

- 后台以桌面端 1280 至 1600 像素宽度为主要使用环境。
- 页面水平留白建议 24 像素，紧凑页面不低于 16 像素。
- 页面标题、范围、状态、主要操作和内容区形成稳定层级。
- 统计卡片只展示需要快速判断的指标，不为装饰堆卡片。
- 一个页面只允许一个最突出的主要操作。
- 危险操作不与普通操作使用相同视觉权重。

### 11.3 状态表达

- 状态不能只靠颜色。
- 状态标签使用中文，同时保留必要的技术状态说明。
- `APPROVED`、`PUBLISHED`、`INDEXING` 等不同状态不能混用中文。
- 加载中不得短暂展示“暂无数据”。
- 接口失败不得降级成全零统计或空列表。
- 空状态必须解释为空原因，并提供可执行下一步。

### 11.4 可访问性

- 键盘可以操作导航、Tab、筛选器和主要按钮。
- 折叠菜单保留可访问名称。
- 焦点状态清晰可见。
- 图标按钮提供 `aria-label` 或可见文字。
- 状态图表同时提供文字结论。
- 文字与背景对比度满足基本可读性要求。

## 12. 当前接口缺口和实施边界

以下问题不能通过前端美化解决，必须在实施文档和页面中如实处理：

| 问题 | 当前处理 |
| --- | --- |
| 目录 `/search` 未实际应用 `datasourceId` | 数据源内目录使用 `/entities?datasourceId=...` 后前端过滤；全局搜索才使用 `/search` |
| 工作台统计只允许 `*` | 本轮使用 `*` 超级管理员账号，直接使用统计接口；普通角色工作台留待权限系统重构 |
| 慢查询不支持数据源筛选 | 性能分析隐藏数据源上下文；记录后端增强需求 |
| 访问审批列表未按申请人收窄 | 本轮只做超级管理员审批队列；普通用户“我的申请”暂不正式开放 |
| 告警没有执行和历史闭环 | 仅提供“告警规则”配置，不显示告警中心和历史统计 |
| 独立分类体系 CRUD 未找到 | 不制作虚假的分类管理页；字段标签继续使用现有接口 |
| 系统健康仅 `*` | 本轮超级管理员可完整使用；新权限系统阶段再规划细分权限 |
| 当前没有统一待办聚合 API | 前端可组合已有列表和就绪度；若请求过多则记录后端聚合接口需求 |

### 12.1 后续变更（2026-09-12）

2026-09-12 进行了一次**独立的后端缺陷修复轮**（不属于本轮前端重构，不受 §1「不修改 Java」约束），
下表缺口已闭合。阶段 6–8 实施时按下表的新事实处理，不要再套用上表的旧规避方案。

| 已闭合的缺口 | 新事实 |
| --- | --- |
| 目录 `/search` 未实际应用 `datasourceId` | 过滤已下推到 SQL（按 `entity_metadata.datasource_id`），分页条数与实际匹配数一致。数据源内搜索可直接用 `/search?datasourceId=`，不再需要「拉全量后前端过滤」。现有页面（资产目录、术语关联字段）尚未切换，属阶段 8 收敛项 |
| 审核意见已落库但无查询接口 | 新增 `GET /api/admin/knowledge-docs/{id}/review-tasks`，返回版本号、审核结果、审核人姓名、审核意见与时间。§16.3「审核拒绝后能够返回编辑并看到原因」现已可验收 |
| 向量化任务不可查询 | 新增 `GET /api/admin/knowledge-docs/{id}/vector-tasks`。§7.11「`INDEXING`：显示进度和失败信息」现已可实施 |
| Prompt 模板不能停用 | 新增 `PATCH /api/admin/prompt-templates/{code}/enabled`，仅允许对 APPROVED 模板启停 |
| 术语无 `APPROVED → DRAFT` 退回落口 | 新增 `POST /api/admin/glossary/terms/{termId}/revert`；`updateTerm` 同时加了状态校验（只允许 DRAFT/REJECTED），请求体不再能篡改状态与审核记录 |
| `knowledge_doc_version.review_status` 是死列 | 自 V51 起由 approve/reject 真实写入；历史行按审核任务还原或标为 `UNKNOWN`。可按版本展示审核状态 |

**同日稍后补齐的三项**（提交 `7de957c`）：

| 缺口 | 新事实 |
| --- | --- |
| 问题中心的责任人筛选 | `/quality-issues` 两个端点支持 `assigneeId` 参数（下推到 SQL）；前端已加责任人下拉、「分配给我的」快捷与责任人列。**「分配给我的」是显式筛选而非默认开启**——§7.7 原文是「默认**优先**展示」，强制默认过滤会让首次进入看到空列表；要落实需后端按责任人排序。此取舍未经产品确认 |
| 无「按文档查来源快照」的聚合接口 | 新增 `GET /api/admin/knowledge-docs/{id}/source-snapshots`，按版本返回快照版本号/状态/规模。原前端只能显示裸 ID，因为跨数据源版本引用的快照可能不在已加载的分页范围内 |
| §3.12 审批列表未按申请人收窄 | `listRequests` 原**无任何权限限制**，任何登录用户可列出全部申请（含他人申请理由）。现由后端强制收窄：有 `security:manage` 看全量，其余只看自己提交的。「我的申请」的数据隔离已成立，但正式开放该入口仍需产品确认 |

**仍存在的缺口**：

| 缺口 | 说明 |
| --- | --- |
| 旧规避方案未回收 | 资产目录与术语关联字段仍在客户端过滤（`/search?datasourceId=` 已可用）；`resolveReadinessActionPath` 的 `focus` 参数已改为直接落数据源详情，列表页仍不消费它 |
| 「默认优先展示分配给我的问题」未落实 | 见上表：需后端按责任人排序，本次只做了显式筛选 |

## 13. 本轮全权限开发基线

本轮不开发新的权限体系，也不以现有细粒度权限决定菜单和页面布局。

- 开发、联调和最终验收使用真实拥有 `*` 权限的超级管理员账号。
- 七个一级业务域、全部工作区、Tab 和操作对该账号可见。
- 前端只保留登录校验和必要的后台入口校验，不投入时间实现现有权限码的复杂组合跳转。
- 后端现有 `@PreAuthorize`、数据范围、安全规则和状态机全部保留；前端不得写死或伪造 `*`。
- 授权管理、访问审批、用户、角色、部门和权限项仍按已有接口完成页面重组，因为它们是现有后台功能，不等同于本轮重做权限模型。
- 前端信息架构稳定后，另立任务重新设计菜单权限、页面权限、Tab 权限、操作权限、数据范围和角色模板。

因此，本轮验收不再要求多种权限组合，只要求超级管理员能够完整访问并完成全部后台流程。

## 14. 实施顺序

不要一次性删除旧后台。采用“新容器先完成、能力迁移验证后再重定向和删除”的方式。

### 阶段 0：建立基线

1. 保存当前 V1 修改状态，禁止整体回退丢失证据。
2. 记录当前所有后台路由、页面和 API 调用。
3. 建立旧页面到新工作区的迁移矩阵。
4. 确定统一设计令牌、状态标签和页面容器规范。

### 阶段 1：新后台壳层和工作台

1. 重建一级导航、二级工作区和页面标题结构。
2. 建立统一数据源/快照范围模型。
3. 使用 readiness 接口完成工作台和下一步引导。
4. 建立 readiness `actionPath` 的旧路径到新路径映射；新页面不得直接散落处理旧 URL。
5. 保留旧页面路由，不提前重定向。

### 阶段 2：数据源上线主流程

1. 重建数据源列表和创建流程。
2. 建立数据源上线驾驶舱。
3. 整合连接、采集任务和同步调度。
4. 完成从连接到快照的下一步引导。

### 阶段 3：资产和版本发布

1. 整合目录搜索和表字段浏览。
2. 建立资产详情。
3. 整合快照列表、审核、发布、版本历史和差异。
4. 完成采集成功到发布快照的流程。

### 阶段 4：治理闭环

1. 重建治理总览和问题中心。
2. 整合质量规则与治理状态。
3. 整合标签、可信度、反馈和脱敏候选。
4. 完成问题处理后返回发布流程的引导。

### 阶段 5：语义发布闭环

1. 重建业务术语工作区。
2. 重建语义知识列表和对象详情。
3. 整合审核、版本、切分和索引状态。
4. 重建 Prompt 列表和详情。
5. 完成快照发布到知识发布的引导。

### 阶段 6：权限和组织

1. 整合数据源授权、细粒度策略和最终权限预览。
2. 新增管理员访问审批页面。
3. 整合用户、角色、部门和权限项。
4. 全部功能使用 `*` 超级管理员账号验证，不在本阶段设计权限组合和新权限模型。

### 阶段 7：运营与平台

1. 整合查询审计和性能分析。
2. 重建全局血缘工作区。
3. 整合服务健康、连接池和告警规则。
4. 保留操作日志和 AI 配置完整能力。

### 阶段 8：迁移和收敛

1. 逐条验证旧页面能力已进入新页面。
2. 添加旧 URL 重定向。
3. 删除不再使用的菜单和页面代码。
4. 清理重复 API 封装和状态映射。
5. 完成视觉、响应式、可访问性和浏览器回归。

## 15. 每阶段交付要求

每个阶段必须同时交付：

- 新页面或工作区。
- 实际后端接口映射。
- 原能力迁移对照表。
- 加载、空、错误、登录失效和阻断状态。
- 上一步和下一步入口。
- URL 刷新、前进和后退恢复。
- 前端生产构建结果。
- 关键流程截图。
- 未完成项和后端缺口。

不能用“页面能打开”代替业务流程完成。

## 16. 验收场景

### 16.1 数据源上线

- 创建数据源前可以测试连接。
- 创建后自动进入数据源详情。
- 连接异常时显示原因和重新测试入口。
- 连接正常后明确引导采集。
- 采集成功后可以进入新快照。
- 快照存在问题时可以直接进入对应问题列表。
- 问题处理后可以返回检查和发布。
- 快照发布后可以生成语义知识。
- 知识发布并配置权限后显示可问数。
- 可问数后可以进入智能问数。

### 16.2 上下文隔离

- 连续切换两个数据源不会保留旧快照和旧字段。
- 直接打开快照、资产和知识详情能够正确解析绑定对象。
- URL 中的合法对象不因分页而失效。
- 锁定对象页面没有可造成对象跳变的全局选择器。

### 16.3 生命周期

- 快照只能显示当前状态允许的操作。
- 快照批准与发布明确区分。
- 知识批准、索引和发布明确区分。
- 审核拒绝后能够返回编辑并看到原因。
- 发布、撤回和回滚均能查看历史记录。

### 16.4 本轮访问与授权体验

- `*` 超级管理员可以看到全部一级业务域、工作区、Tab 和合法操作。
- 前端不伪造权限，所有请求仍经过后端校验。
- 授权配置可以查看最终计算结果。
- 管理员不会因顶部和页面数据源不一致而误授权。
- 普通角色的菜单和操作权限组合明确标记为后续权限系统任务，不写成已验证。

### 16.5 异常体验

- 接口失败显示错误，不显示假空数据。
- 没有数据时说明原因和创建方式。
- 后台任务运行中显示进行中状态并避免重复操作。
- 部分接口不可用时保留其他可用区域。
- 危险操作显示对象、影响和确认信息。

## 17. 完成定义

后台前端整体重构只有同时满足以下条件才算完成：

1. 第 4 节目标菜单和业务归属已经落地，或经审查形成更优且有依据的替代方案。
2. 后端已实现的管理能力全部具有合理前端归属，未承载能力有明确原因。
3. 数据源接入到可问数的主流程可以连续完成。
4. 每个关键页面都能说明当前状态、阻断原因和下一步。
5. 列表、详情、审核、版本、发布和审计不再成为互相割裂的孤岛。
6. 新前端路由能够刷新和恢复必要状态。
7. 旧能力迁移后无功能丢失。
8. 现有后端权限和安全校验保持有效，本轮以真实 `*` 超级管理员账号完成访问。
9. 前端构建通过，并完成真实数据和超级管理员完整流程的浏览器验证。
10. 新前端信息架构稳定，可以作为后续权限系统重新设计的业务基础。

## 18. 明确禁止事项

- 不为了少改代码而保留不合理页面。
- 不以当前 URL 为理由牺牲新流程。
- 不先删旧页面再补新能力。
- 不把接口存在写成运行态已经验证。
- 不把只有数据库表的能力写成已有完整 API。
- 不用前端状态绕过后端业务状态机。
- 不用前端隐藏代替后端权限和数据范围控制。
- 不把审核通过、发布成功和索引完成混成同一状态。
- 不显示不生效的数据源或快照选择器。
- 不把告警规则 CRUD 包装成完整告警闭环。
- 不在后台整体结构稳定前实施新的权限体系。

## 19. 与旧文档的关系

本文取代《DataOcean 后台菜单与页面布局重构指导》作为后续后台前端开发的主要指导文档。

当前生效文档只有：

1. 《DataOcean 后台前端整体重构任务与实施指导》：定义目标、范围和决策原则。
2. 本文：定义信息架构、页面、路由、接口边界和体验标准。
3. 《DataOcean 后台前端整体重构实施任务清单》：定义实施批次、文件迁移、验收和交付证据。

以下文档不再作为开发依据：

- 《DataOcean 现有功能盘点与业务流程及菜单重构任务》：早期盘点材料。
- 《DataOcean 后台菜单与页面布局重构指导》：旧菜单方案。
- 《DataOcean 后台菜单 V1 重构实施总结》：历史实施记录。
- 《DataOcean 后台菜单 V1 重构审查意见》：历史审查记录。
- 《DataOcean 完整权限体系设计》：后续权限重构输入，本轮不得实施。

这些文档可以移入 `docs/development/archive/`。如果选择删除，必须先确认其有效事实已被当前三份生效文档吸收，并清理生效文档中的旧引用。权限设计文档应优先归档而不是删除。
