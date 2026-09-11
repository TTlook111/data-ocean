# DataOcean 后台菜单与页面布局重构指导

> 文档状态：菜单与布局重构开发实施指导稿  
> 更新日期：2026-09-11  
> 适用范围：DataOcean 后台管理端  
> 不在范围：智能问数页面、后端业务逻辑、数据库结构、Agent / RAG 工作流

## 1. 结论

`DataOcean-现有功能盘点与业务流程及菜单重构任务.md` 的核心方向合理：先确认真实能力和业务流程，再设计菜单，而不是按 Controller、数据表或已有页面数量堆菜单。

但原任务文档同时要求功能盘点、流程设计、菜单重构、权限候选、数据权限、接口重构和实施计划，单次交付范围过大。菜单改造阶段应只回答四个问题：

1. 后台有哪些稳定业务域。
2. 用户在每个业务域完成什么任务。
3. 哪些能力应进入一级菜单、二级导航、详情页、Tab 或操作按钮。
4. 现有页面如何迁移且尽量不改后端能力。

因此，本指导采用以下顺序：

```text
真实能力与边界确认
-> 数据从接入到可问数的流程确认
-> 页面职责划分
-> 菜单与布局设计
-> 现有路由迁移
-> 权限和接口作为后续输入
```

## 2. 当前实现基线

本指导基于以下当前代码和文档：

- `frontend/src/components/AppShell.vue`：一级菜单、二级工作区导航和后台整体外壳。
- `frontend/src/router/index.ts`：后台页面、路由、标题、业务域和页面权限。
- `frontend/src/components/AdminContextBar.vue`：数据源、快照、知识文档上下文。
- `frontend/src/views/admin/datasource/DatasourceLifecycle.vue`：数据源从接入到可问数的就绪流程。
- `docs/development/completed/后台信息架构与导航规范.md`：2026-06-24 的既有导航约束。
- `docs/development/项目真实状态看板.md` 与 `docs/development/后续开发.md`：当前完成状态和未完成项。

当前后台已经采用“一级业务域侧边栏 + 内容区二级导航”的基本结构，方向无需推翻。主要需要重新统一命名、页面归属和深层页面入口。

当前一级入口为：

```text
工作台
数据源中心
治理中心
语义中心
权限与开放
运营与安全
系统设置
```

当前结构的主要问题：

1. “数据源中心”实际同时承载数据源、元数据、数据目录、快照和发布，名称小于实际职责。
2. 侧边栏名称、路由 `meta.section` 和页面内标题存在“数据源中心 / 数据资产”“治理中心 / 治理工作台”“语义中心 / 语义资产”等多套说法。
3. 快照列表、快照差异、版本历史、同步调度、可信度和反馈等路由仍存在，但不在当前工作区导航中；部分是合理的深层页面，部分缺少清晰入口。
4. “操作日志”当前归入系统设置，但页面内容和审计用途更符合运营与审计。
5. 权限域把用户、角色、部门、访问决策和策略平铺为同级入口，组织管理与数据授权任务没有分组。
6. 固定展示“数据源 + 快照 + 知识库”的上下文条并不适用于所有后台页面，应按业务域和页面任务动态展示。
7. 快照发布与知识发布是两个不同生命周期，不能合并成一个含义模糊的“发布管理”。

### 2.1 交付版本边界

为避免把菜单调整和页面重写混在一次开发中，本次重构拆成两个版本。

#### V1：导航归一

V1 可以直接开发，范围固定为：

- 调整一级菜单名称、分组、图标归属和匹配范围。
- 调整二级工作区导航名称、顺序和入口。
- 为被移出二级导航的现有页面补充页内操作入口。
- 统一 `route.meta.section`、`route.meta.title` 和页面内标题。
- 将操作日志的业务归属调整到“运营与审计”。
- 按路由元数据控制上下文条。
- 隐藏详情、编辑、审核、版本、差异等深层页面的独立导航入口。
- 保留全部现有 URL、页面组件、后端接口和权限码。

V1 不进行：

- 页面合并或业务组件重写。
- 新增访问审批页面。
- 新增告警页面。
- 权限数据库或后端接口调整。
- 智能问数页面调整。

#### V2：工作区整合

V2 在 V1 验证通过后实施，范围固定为：

- 数据目录与表浏览器整合。
- 快照、版本、差异和发布整合。
- 字段可信度与反馈审核整合。
- 用户、角色、部门整合为“组织与角色”。
- 语义知识编辑、审核和版本整合为对象详情流程。
- 为被替代的旧 URL 增加重定向。

访问审批和告警能力不属于 V2 页面整合；它们应在各自功能闭环完成后单独实施。

## 3. 当前真实能力与菜单边界

| 业务能力 | 当前承载位置 | 菜单判断 |
| --- | --- | --- |
| 数据源创建、编辑、启停、连接测试 | 数据源列表及接口 | 保留稳定二级入口“数据源” |
| 元数据同步、同步任务、同步调度 | 元数据同步相关页面 | 合并为“采集与同步”；调度放页面内 Tab |
| 快照、差异、版本、审核与发布 | 快照相关页面 | 合并为“版本与发布”；详情和差异不单独出菜单 |
| 表、字段、实体和目录检索 | 表浏览器、目录搜索 | 统一为“数据目录”；表和字段进入资产详情 |
| 质量检查、治理问题和治理状态 | 治理页面 | 保留在“数据治理” |
| 分类、标签、敏感候选和字段可信度 | 字段标签、可信度、目录接口 | 放入治理域，不单独设“字段管理”一级入口 |
| skills.md、知识审核、版本与向量发布 | 知识页面 | 合并为“语义知识”；编辑、审核、版本是页内状态或深层页面 |
| 业务术语 | 术语管理页面 | 保留“业务术语” |
| Prompt 模板与审批 | Prompt 页面 | 保留“Prompt 策略”，归语义中心 |
| 数据源访问、表列策略、行过滤和脱敏 | 访问控制、策略编辑器 | 保留在“权限中心” |
| 用户、角色、部门、功能权限 | 用户、角色、部门页面 | 合并为“组织与角色”工作区，页内分 Tab |
| 访问审批 | 后端接口已存在，当前无完整前端入口 | 作为权限中心待补页面，不标记为已交付菜单 |
| 查询审计、慢查询、血缘 | 审计与血缘页面 | 统一归“运营与审计” |
| 服务健康 | 服务健康页面 | 归“运营与审计”的运行健康 |
| 操作日志 | 操作日志页面 | 从系统设置迁到“运营与审计” |
| 告警规则和告警历史 | 规则 CRUD/API 有基础，执行闭环和前端仍在后续计划 | 暂不进入正式菜单；闭环完成后进入“运行监控”的页面 Tab |
| AI 供应商、模型、Embedding 配置 | AI 配置页面 | 保留在“平台设置” |
| 问数会话、结果、SQL、图表和历史 | 智能问数页面 | 不进入本次后台菜单重构 |

以下概念不应因为存在后端对象而创建独立菜单：

- Database / Schema：当前 MVP 是单次选择一个 MySQL 数据源，在该数据库内多表查询，应通过数据源和数据目录表达。
- Table / Column：是资产层级，不是一级业务域；放在目录和资产详情页。
- Join Path：属于表关系或语义知识内容，放在资产详情关系 Tab 或语义知识编辑中。
- Metric：当前不应虚构独立指标中心；已经审核的指标定义继续由语义知识承载。
- SQL 校验、SQL 执行、Schema RAG、向量索引：是系统能力和流程状态，不是普通管理员的独立菜单。
- 发布：快照发布和知识发布分别属于数据资产与语义知识生命周期，不设跨对象的统一“发布”菜单。

## 4. DataOcean 后台主流程

最终主流程应以“数据源何时可以被问数”为主线：

```text
数据源接入
-> 连接验证
-> 元数据采集并生成快照
-> 数据资产确认
-> 质量与治理处理
-> 语义知识生成、审核和发布
-> 数据访问权限配置
-> 可问数就绪判定
-> 智能问数
-> 查询审计、反馈和持续治理
```

| 阶段 | 用户动作 | 系统动作 | 完成条件 | 主要入口 |
| --- | --- | --- | --- | --- |
| 数据源接入 | 创建连接并测试 | 保存加密连接信息、执行连通性检查 | 连接可用且数据源启用 | 数据资产 / 数据源 |
| 元数据采集 | 发起同步或配置调度 | 采集表、字段、关系和样例元数据，生成快照 | 存在可用快照 | 数据资产 / 采集与同步 |
| 数据资产确认 | 浏览目录、表和字段 | 建立资产实体和关系 | 资产范围可识别 | 数据资产 / 数据目录 |
| 治理处理 | 处理质量问题、状态、标签和敏感候选 | 计算质量分、记录治理事件 | 无阻断性治理问题 | 数据治理 |
| 语义发布 | 维护、审核并发布 skills.md | 切分、向量化、校验并切换已发布版本 | 存在已发布且索引成功的知识版本 | 语义中心 / 语义知识 |
| 权限开放 | 配置数据源、表、列、行和脱敏策略 | 合并用户、角色、部门与策略结果 | 当前用户获得有效访问决策 | 权限中心 |
| 就绪判定 | 查看阻断原因并跳转处理 | 聚合连接、快照、治理、知识和权限状态 | `askable = true` | 数据源详情 / 工作台 |
| 持续运营 | 查看审计、慢查询、血缘和反馈 | 记录查询、SQL、访问与操作轨迹 | 问题被跟踪并回流治理 | 运营与审计 |

菜单表达业务域，流程由工作台、数据源详情页和阻断原因跳转串联。不要把每个流程步骤机械变成一级菜单。

## 5. 最终信息架构

后台采用四层承载方式：

```text
一级侧边栏：稳定业务域
二级工作区导航：该业务域内的高频任务
页面内 Tab：同一对象或同一任务的不同视角
详情页 / Drawer / Dialog / 操作按钮：低频、上下文相关或不可独立完成的动作
```

### 5.1 最终菜单树

```text
DataOcean 后台
│
├── 工作台
│
├── 数据资产
│   ├── 数据源
│   ├── 数据目录
│   ├── 采集与同步
│   └── 版本与发布
│
├── 数据治理
│   ├── 治理总览
│   ├── 问题处理
│   ├── 治理状态
│   ├── 分类与标签
│   └── 可信度与反馈
│
├── 语义中心
│   ├── 业务术语
│   ├── 语义知识
│   └── Prompt 策略
│
├── 权限中心
│   ├── 访问控制
│   ├── 授权策略
│   ├── 访问审批（前端补齐后显示）
│   └── 组织与角色
│
├── 运营与审计
│   ├── 查询审计
│   ├── 性能分析
│   ├── 数据血缘
│   ├── 运行监控
│   └── 操作日志
│
└── 平台设置
    └── AI 配置
```

一级菜单保持 7 个稳定入口。未来新增页面时，优先进入已有业务域；只有出现新的、长期稳定且包含多个独立任务的业务域时，才考虑增加一级菜单。

### 5.2 V1 一级菜单配置

V1 继续在 `AppShell.vue` 的 `menuGroups` 中维护菜单，不引入新的导航配置层。

侧边栏分组固定为：

```text
总览
  工作台

数据生命周期
  数据资产
  数据治理
  语义中心
  权限中心

运营与平台
  运营与审计
  平台设置
```

| key | 一级菜单 | 默认地址 | match 范围 | 一级入口可见条件 |
| --- | --- | --- | --- | --- |
| `workbench` | 工作台 | `/admin` | 只匹配 `/admin` | 可以进入后台 |
| `assets` | 数据资产 | `/admin/datasources` | `/admin/datasources`、`/admin/metadata` | 存在任一可访问的数据资产二级页面 |
| `governance` | 数据治理 | `/admin/governance/quality` | `/admin/governance`、`/admin/field` | 存在任一可访问的数据治理二级页面 |
| `semantic` | 语义中心 | `/admin/knowledge` | `/admin/knowledge`、`/admin/glossary`、`/admin/prompts` | 存在任一可访问的语义中心二级页面 |
| `permission` | 权限中心 | `/admin/permission/access` | `/admin/permission`、`/admin/users`、`/admin/roles`、`/admin/departments` | 存在任一可访问的权限中心二级页面 |
| `operation` | 运营与审计 | `/admin/audit/logs` | `/admin/audit`、`/admin/system/health`、`/admin/system/operation-logs` | 存在任一可访问的运营与审计二级页面 |
| `settings` | 平台设置 | `/admin/system/ai-config` | `/admin/system/ai-config` | 具有 AI 配置查看或管理权限 |

`routeMatches` 必须优先使用最长匹配路径；`navTarget` 必须进入当前用户有权访问的第一个二级页面，不能固定跳到无权访问的默认地址。

### 5.3 V1 二级工作区导航配置

V1 只使用当前已经存在的页面。尚未合并的页面暂时保留独立入口，避免出现无法到达的孤立页面。

| 一级菜单 | 二级菜单 | 当前路由 | 当前权限 |
| --- | --- | --- | --- |
| 数据资产 | 数据源 | `/admin/datasources` | `datasource:manage` |
| 数据资产 | 数据目录 | `/admin/metadata/catalog` | `metadata:manage` |
| 数据资产 | 采集与同步 | `/admin/metadata/sync` | `metadata:manage` |
| 数据资产 | 版本与发布 | `/admin/metadata/lifecycle` | `metadata:manage` |
| 数据治理 | 治理总览 | `/admin/governance/quality` | `metadata:manage` |
| 数据治理 | 问题处理 | `/admin/governance/issues` | `metadata:manage` |
| 数据治理 | 治理状态 | `/admin/governance/status` | `metadata:manage` |
| 数据治理 | 分类与标签 | `/admin/field/tags` | `field-tag:manage` |
| 数据治理 | 字段可信度 | `/admin/field/confidence` | `field-tag:manage` |
| 数据治理 | 反馈审核 | `/admin/field/feedback-review` | `field-tag:manage` |
| 语义中心 | 业务术语 | `/admin/glossary/list` | `metadata:manage` |
| 语义中心 | 语义知识 | `/admin/knowledge` | `knowledge:manage` |
| 语义中心 | Prompt 策略 | `/admin/prompts` | `prompt:manage` |
| 权限中心 | 访问控制 | `/admin/permission/access` | `security:manage` |
| 权限中心 | 授权策略 | `/admin/permission/policies` | `security:manage` |
| 权限中心 | 用户管理 | `/admin/users` | `user:manage` |
| 权限中心 | 角色管理 | `/admin/roles` | `role:view` |
| 权限中心 | 部门管理 | `/admin/departments` | `department:manage` |
| 运营与审计 | 查询审计 | `/admin/audit/logs` | `audit:view` |
| 运营与审计 | 性能分析 | `/admin/audit/slow-queries` | `audit:view` |
| 运营与审计 | 数据血缘 | `/admin/audit/data-lineage` | `audit:view` |
| 运营与审计 | 运行监控 | `/admin/system/health` | 保持当前权限，不在 V1 重构 |
| 运营与审计 | 操作日志 | `/admin/system/operation-logs` | `audit:view` |
| 平台设置 | AI 配置 | `/admin/system/ai-config` | `system:ai-config:view`；`system:ai-config:manage` 兼容可见 |

V1 不在菜单中展示：

- `/admin/metadata/tables`
- `/admin/metadata/snapshots`
- `/admin/metadata/diff`
- `/admin/metadata/version-history`
- `/admin/metadata/schedule`
- `/admin/knowledge/editor/:id?`
- `/admin/knowledge/versions/:id`
- `/admin/knowledge/review`
- `/admin/audit/lineage`
- `/admin/audit/lineage-graph`

这些页面继续通过列表、详情、按钮或现有重定向进入。

V1 必须补齐以下页内入口，避免隐藏菜单后形成孤立页面：

| 承载页面 | 新增或保留的操作入口 | 目标路由 |
| --- | --- | --- |
| 数据目录 | “表浏览器”辅助入口 | `/admin/metadata/tables` |
| 采集与同步 | “同步调度”入口 | `/admin/metadata/schedule` |
| 版本与发布 | “快照列表”入口 | `/admin/metadata/snapshots` |
| 版本与发布 | “差异比较”入口 | `/admin/metadata/diff` |
| 版本与发布 | “版本历史”入口 | `/admin/metadata/version-history` |
| 语义知识 | “审核队列”入口 | `/admin/knowledge/review` |

语义知识列表中已经存在的编辑和版本操作继续进入 `/admin/knowledge/editor/:id?` 与 `/admin/knowledge/versions/:id`，无需增加二级菜单。

### 5.4 菜单职责

| 一级菜单 | 二级菜单 | 负责什么 | 不负责什么 | 主要用户 |
| --- | --- | --- | --- | --- |
| 工作台 | 无固定二级菜单 | 待办、阻断、就绪度、关键指标和快捷入口 | 完整配置和明细编辑 | 平台管理员、治理人员 |
| 数据资产 | 数据源 | 连接创建、测试、启停、同步入口和就绪概览 | 深度治理、知识编辑、复杂权限策略 | 数据源管理员 |
| 数据资产 | 数据目录 | 按数据源浏览和搜索表、字段、实体关系 | 发布审批和系统配置 | 数据管理员、治理人员 |
| 数据资产 | 采集与同步 | 手动同步、任务记录、失败重试、调度配置 | 质量问题处理 | 元数据管理员 |
| 数据资产 | 版本与发布 | 快照列表、差异、版本、审核和发布状态 | skills.md 发布 | 元数据管理员、审核人 |
| 数据治理 | 治理总览 | 质量评分、问题分布和治理进度 | 具体数据源连接配置 | 治理负责人 |
| 数据治理 | 问题处理 | 问题筛选、分派、处理、复开和批量状态 | 数据目录浏览 | 数据治理人员 |
| 数据治理 | 治理状态 | 表和字段治理状态、阻断状态 | 功能权限配置 | 数据治理人员 |
| 数据治理 | 分类与标签 | 分类、标签、敏感候选确认 | 脱敏执行策略 | 数据治理人员、安全管理员 |
| 数据治理 | 可信度与反馈 | 字段可信度、趋势、用户反馈审核 | 查询结果展示 | 数据治理人员 |
| 语义中心 | 业务术语 | 术语、同义词和审核 | 通用系统参数 | 业务专家、语义管理员 |
| 语义中心 | 语义知识 | skills.md 生成、编辑、审核、版本、发布与索引状态 | 元数据快照发布 | 业务专家、知识审核人 |
| 语义中心 | Prompt 策略 | Prompt 模板、审批、版本和回滚 | AI 供应商连接配置 | AI 管理员、审核人 |
| 权限中心 | 访问控制 | 查看合并后的用户/角色/部门/数据源访问结果 | 策略细节编辑 | 权限管理员、审计人员 |
| 权限中心 | 授权策略 | 数据源、表、列、行过滤和脱敏策略 | 组织成员维护 | 权限管理员 |
| 权限中心 | 访问审批 | 申请列表、审核、临时授权和到期状态 | 永久组织权限维护 | 权限审批人 |
| 权限中心 | 组织与角色 | 用户、角色、部门和功能权限 | 数据行列策略 | 系统管理员 |
| 运营与审计 | 查询审计 | 问题、SQL、执行、结果与风险追踪 | 问数会话交互 | 审计人员、运维人员 |
| 运营与审计 | 性能分析 | 慢查询、耗时和失败分析 | SQL 在线执行 | 运维人员 |
| 运营与审计 | 数据血缘 | 表列血缘、影响分析和关系维护 | 一般数据目录编辑 | 数据治理人员、审计人员 |
| 运营与审计 | 运行监控 | Java、Python、数据库、Redis、Milvus、连接池状态；闭环完成后承载告警规则和历史 Tab | AI 模型业务配置、未执行的空壳告警 | 运维人员 |
| 运营与审计 | 操作日志 | 后台管理操作追溯 | 查询业务审计 | 审计人员、系统管理员 |
| 平台设置 | AI 配置 | 模型供应商、模型、Embedding、连通性和重建入口 | Prompt 内容、知识审核 | 平台管理员、AI 管理员 |

## 6. 页面布局指导

### 6.1 后台外壳

`AppShell.vue` 继续承担后台统一外壳，布局顺序固定为：

```text
左侧一级业务域导航
+ 顶部页面标题和全局动作
+ 可选面包屑
+ 可选业务上下文条
+ 二级工作区导航
+ 页面内容
```

要求：

- 左侧只放一级业务域，不放同步任务、字段标签、用户、角色等具体功能。
- 一级菜单点击后进入当前用户有权访问的第一个二级页面。
- 一级菜单、`route.meta.section`、面包屑和页面标题必须使用同一套业务域名称。
- 工作区导航建议控制在 3 至 5 个稳定入口；更多内容下沉到页面 Tab 或操作入口。
- 一级导航折叠后保留图标和可访问名称，不能只依赖悬浮提示理解当前业务域。

### 6.2 上下文条

上下文条按页面任务动态显示，不再所有相关页面固定展示三个选择器。

| 页面类型 | 数据源 | 快照 | 知识文档 |
| --- | --- | --- | --- |
| 数据源列表 | 不显示 | 不显示 | 不显示 |
| 数据目录 | 必选 | 默认已发布快照，可切换 | 不显示 |
| 采集与同步 | 必选 | 按任务需要显示 | 不显示 |
| 版本与发布 | 必选 | 必选 | 不显示 |
| 数据治理 | 必选 | 必选 | 不显示 |
| 语义知识列表 | 可选筛选 | 不显示 | 由页面列表选择，不进入全局上下文 |
| 语义知识详情或编辑 | 由文档绑定关系确定 | 只读展示来源快照 | 由路由 `id` 锁定 |
| 权限中心 | 按策略范围可选 | 不显示 | 不显示 |
| 运营与审计 | 作为筛选条件可选 | 通常不显示 | 不显示 |
| 平台设置 | 不显示 | 不显示 | 不显示 |

上下文切换必须同步更新页面数据。若页面本身已经由路由参数锁定对象，不再展示可造成对象跳变的全局选择器。

V1 使用路由元数据控制上下文条，不再由 `AppShell.vue` 的 `contextRoutes` 路径前缀数组判断：

```ts
type AdminContextMode =
  | 'none'
  | 'datasource'
  | 'datasource-snapshot'
  | 'locked-resource'

meta: {
  title: '数据目录',
  section: '数据资产',
  domainKey: 'assets',
  workspaceKey: 'catalog',
  contextMode: 'datasource-snapshot',
}
```

| 路由 | `domainKey` | `workspaceKey` | `contextMode` |
| --- | --- | --- | --- |
| `/admin` | `workbench` | `workbench` | `none` |
| `/admin/datasources` | `assets` | `datasource` | `none` |
| `/admin/datasources/:id/lifecycle` | `assets` | `datasource` | `locked-resource` |
| `/admin/metadata/catalog`、`/admin/metadata/tables` | `assets` | `catalog` | `datasource-snapshot` |
| `/admin/metadata/sync`、`/admin/metadata/schedule` | `assets` | `collection` | `datasource` |
| `/admin/metadata/lifecycle`、快照相关深层路由 | `assets` | `release` | `datasource-snapshot` |
| `/admin/governance/*`、`/admin/field/*` | `governance` | 对应二级任务 | `datasource-snapshot` |
| `/admin/knowledge`、`/admin/glossary/list`、`/admin/prompts` | `semantic` | 对应二级任务 | `none` |
| `/admin/knowledge/editor/:id?`、`/admin/knowledge/versions/:id` | `semantic` | `knowledge` | `locked-resource` |
| `/admin/permission/*` | `permission` | 对应二级任务 | `datasource` |
| `/admin/users`、`/admin/roles`、`/admin/departments` | `permission` | 对应组织任务 | `none` |
| `/admin/audit/*` | `operation` | 对应二级任务 | `datasource` |
| `/admin/system/health`、`/admin/system/operation-logs` | `operation` | 对应二级任务 | `none` |
| `/admin/system/ai-config` | `settings` | `ai-config` | `none` |

上下文选择规则固定为：

1. `none`：不显示上下文条。
2. `datasource`：只显示数据源选择器。
3. `datasource-snapshot`：显示数据源和快照，不显示知识文档选择器。
4. `locked-resource`：不显示可交互的全局上下文条；由当前页面展示路由对象的只读摘要。数据源详情展示数据源摘要，知识编辑和版本页展示绑定的数据源、来源快照和知识文档摘要。
5. 切换数据源时先清空旧快照和旧知识文档，再请求新数据源对应数据。
6. 数据目录优先选择当前数据源的已发布快照；没有已发布快照时选择最新快照，并明确显示“未发布”。
7. URL 中存在合法的 `datasourceId` 或 `snapshotId` 查询参数时以 URL 为准；否则使用已持久化上下文。
8. URL 参数无效时回退到默认对象并替换 URL，不能保留跨数据源的旧对象 ID。

### 6.3 二级导航与页面 Tab

二级导航用于“切换任务”，Tab 用于“查看同一任务或对象的不同方面”。

正确示例：

```text
数据资产（二级导航）
  数据源 | 数据目录 | 采集与同步 | 版本与发布

版本与发布（页面内 Tab）
  快照 | 发布记录 | 差异比较 | 版本历史
```

错误示例：把“快照列表、快照差异、版本历史、同步调度”全部放进二级导航。

### 6.4 面包屑

面包屑只在详情、编辑、审核、版本、差异等深层页面显示：

```text
数据资产 / 数据源 / 订单库 / 就绪度
语义中心 / 语义知识 / 销售主题知识 / v5
```

面包屑的中间节点必须可返回真实列表或详情页，不能仅由平铺路由的 `matched.length` 推断层级。

V1 不调整成多层嵌套路由，统一通过路由元数据生成面包屑：

```ts
meta: {
  title: '版本详情',
  section: '数据资产',
  domainKey: 'assets',
  workspaceKey: 'release',
  breadcrumbParent: '/admin/metadata/lifecycle',
}
```

生成顺序固定为：

```text
section
-> workspaceKey 对应的二级菜单
-> breadcrumbParent 对应的父页面
-> 当前 title
```

规则：

- 一级业务域首页和二级工作区首页不显示面包屑。
- 详情、编辑、审核、版本、差异页面必须配置 `breadcrumbParent`。
- `breadcrumbParent` 必须是实际可访问路由。
- 当前页面不重复出现在面包屑中。
- 权限不足的父页面不生成可点击链接，只显示文本。

## 7. 详情页设计

### 7.1 数据源详情

数据源详情是生命周期总入口，不是把所有后台页面复制成一组大 Tab。

V2 固定 Tab：

| Tab | 内容 | 允许的主要动作 |
| --- | --- | --- |
| 概览 | 基本信息、连接健康、当前阶段、就绪进度、首个阻断原因 | 编辑基本信息、跳到下一步 |
| 连接 | 主机、端口、数据库、账号状态和最近测试结果 | 测试连接、启停 |
| 采集与版本 | 最近同步任务、当前快照、发布版本摘要 | 发起同步、查看快照、进入版本与发布 |
| 可问数就绪度 | 连接、元数据、治理、知识、权限五个独立维度 | 按阻断原因跳转到责任页面 |
| 活动记录 | 与该数据源相关的同步、发布、权限和治理事件 | 查看详情 |

治理、语义和权限在详情页只显示摘要与跳转，不在这里复制完整编辑器。

### 7.2 数据资产详情

表或字段详情从数据目录进入，目标结构固定为：

```text
概览 | 字段 | 关系 | 质量 | 分类与标签 | 血缘 | 权限摘要
```

- “字段”是表详情的 Tab，不是独立一级菜单。
- “关系”展示外键和可审核关系；Join Path 的业务表达由语义知识承接。
- “权限摘要”只说明谁可见、是否脱敏和是否存在行过滤，复杂编辑跳到权限中心。
- 血缘既可在资产详情查看局部关系，也保留运营与审计中的全局工作台。

### 7.3 语义知识详情

语义知识围绕一个知识文档组织：

```text
内容 | 来源与覆盖范围 | 审核记录 | 版本 | 切分预览 | 索引状态
```

“编辑”“审核”“版本历史”不再作为长期并列二级菜单，而是由文档状态和页面动作进入。

### 7.4 V2 页面整合规格

#### 数据目录工作区

| 项目 | 唯一决定 |
| --- | --- |
| 主路由 | `/admin/metadata/catalog` |
| 工作区容器 | 以 `CatalogSearch.vue` 为主页面 |
| 整合来源 | `TableExplorer.vue` 的快照表字段浏览能力 |
| 页面结构 | 搜索与筛选、资产列表、表详情 Drawer 或详情页 |
| 旧路由 | `/admin/metadata/tables` 重定向到 `/admin/metadata/catalog` |
| 完成条件 | 可按数据源和快照搜索、浏览表字段、进入实体详情，原表浏览能力无丢失 |

#### 版本与发布工作区

| 项目 | 唯一决定 |
| --- | --- |
| 主路由 | `/admin/metadata/lifecycle` |
| 工作区容器 | `SnapshotLifecycle.vue` |
| 页面 Tab | `snapshots`、`publish`、`versions` |
| 组件复用 | `SnapshotList.vue` 进入快照 Tab；`VersionHistory.vue` 进入版本 Tab |
| 深层页面 | `SnapshotDiff.vue` 保持差异详情页，不作为 Tab 主体 |
| 旧路由 | `/admin/metadata/snapshots` -> `?tab=snapshots`；`/admin/metadata/version-history` -> `?tab=versions` |
| 完成条件 | 快照列表、审核、发布、撤回、版本历史和差异比较均可到达，刷新保留当前 Tab |

#### 可信度与反馈工作区

| 项目 | 唯一决定 |
| --- | --- |
| 主路由 | `/admin/field/confidence` |
| 工作区容器 | `ConfidenceDashboard.vue` |
| 页面 Tab | `confidence`、`feedback` |
| 组件复用 | `FeedbackReview.vue` 作为反馈 Tab 内容 |
| 旧路由 | `/admin/field/feedback-review` 重定向到 `/admin/field/confidence?tab=feedback` |
| 完成条件 | 可信度列表、趋势、反馈审核、批准和拒绝能力均保留 |

#### 组织与角色工作区

| 项目 | 唯一决定 |
| --- | --- |
| 主路由 | `/admin/permission/organization` |
| 新容器 | 新增 `frontend/src/views/admin/permission/OrganizationWorkspace.vue` |
| 页面 Tab | `users`、`roles`、`departments` |
| 组件复用 | 复用 `UserList.vue`、`RoleList.vue`、`DepartmentTree.vue` |
| 旧路由 | `/admin/users`、`/admin/roles`、`/admin/departments` 分别重定向到对应 Tab |
| 完成条件 | 用户、角色、部门及角色授权能力完整保留，直接刷新可恢复 Tab |

#### 语义知识工作区

| 项目 | 唯一决定 |
| --- | --- |
| 主路由 | `/admin/knowledge` |
| 工作区容器 | `KnowledgeDashboard.vue` |
| 页面 Tab | `documents`、`review` |
| 组件复用 | `ReviewPage.vue` 作为审核 Tab 内容 |
| 深层页面 | `SkillsEditor.vue` 和 `VersionList.vue` 继续承载具体文档的编辑与版本详情 |
| 旧路由 | `/admin/knowledge/review` 重定向到 `/admin/knowledge?tab=review` |
| 完成条件 | 文档列表、生成、编辑、审核、版本、回滚、发布和索引状态均有明确入口 |

## 8. 生命周期状态指导

不要把所有状态塞入单个数据源状态字段。后台至少分别展示：

| 状态维度 | 示例 | 归属页面 |
| --- | --- | --- |
| 数据源启用状态 | 启用 / 停用 | 数据源 |
| 连接健康状态 | 正常 / 异常 / 未检测 | 数据源详情 |
| 同步任务状态 | 等待 / 执行中 / 成功 / 失败 | 采集与同步 |
| 快照状态 | 草稿 / 检查中 / 已批准 / 已发布 / 已撤回 | 版本与发布 |
| 治理状态 | 可用 / 已废弃 / 已阻断及问题状态 | 数据治理 |
| 知识文档状态 | 草稿 / 待审核 / 已批准 / 索引中 / 已发布 | 语义知识 |
| 权限状态 | 未授权 / 有效 / 拒绝 / 临时 / 已过期 | 权限中心 |
| 问数就绪状态 | 可问数 / 不可问数 | 工作台、数据源详情 |

不可问数时必须返回：

```text
阻断维度 + 原因 + 责任角色 + 处理动作 + 可跳转页面
```

现有就绪度模型已经具备连接、元数据、治理、知识、权限和 `blockReasons`，菜单重构应复用该模型，不在前端重新拼一套状态。

## 9. 当前页面迁移指导

V1 保留现有 URL，V2 在页面合并完成后启用重定向。每个路由只允许一种处理状态：

```text
KEEP_VISIBLE：保留并显示在二级导航
KEEP_DEEP：保留页面，只从页内动作进入
MERGE_TARGET：作为 V2 合并后的主页面
REDIRECT：V2 中重定向到唯一目标
PLANNED：能力尚未闭环，本次不注册菜单或路由
```

| 当前路由 | V1 决定 | V2 决定 | V2 唯一目标 |
| --- | --- | --- | --- |
| `/admin` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin` |
| `/admin/datasources` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/datasources` |
| `/admin/datasources/:id/lifecycle` | `KEEP_DEEP` | `MERGE_TARGET` | 原路由 |
| `/admin/metadata/catalog` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/metadata/catalog` |
| `/admin/metadata/tables` | `KEEP_DEEP` | `REDIRECT` | `/admin/metadata/catalog` |
| `/admin/metadata/sync` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/metadata/sync` |
| `/admin/metadata/schedule` | `KEEP_DEEP` | `REDIRECT` | `/admin/metadata/sync?tab=schedule` |
| `/admin/metadata/lifecycle` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/metadata/lifecycle` |
| `/admin/metadata/snapshots` | `KEEP_DEEP` | `REDIRECT` | `/admin/metadata/lifecycle?tab=snapshots` |
| `/admin/metadata/version-history` | `KEEP_DEEP` | `REDIRECT` | `/admin/metadata/lifecycle?tab=versions` |
| `/admin/metadata/diff` | `KEEP_DEEP` | `KEEP_DEEP` | 原路由 |
| `/admin/governance/quality` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/governance/issues` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/governance/status` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/field/tags` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/field/confidence` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/field/confidence?tab=confidence` |
| `/admin/field/feedback-review` | `KEEP_VISIBLE` | `REDIRECT` | `/admin/field/confidence?tab=feedback` |
| `/admin/glossary/list` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/knowledge` | `KEEP_VISIBLE` | `MERGE_TARGET` | `/admin/knowledge?tab=documents` |
| `/admin/knowledge/editor/:id?` | `KEEP_DEEP` | `KEEP_DEEP` | 原路由 |
| `/admin/knowledge/review` | `KEEP_DEEP` | `REDIRECT` | `/admin/knowledge?tab=review` |
| `/admin/knowledge/versions/:id` | `KEEP_DEEP` | `KEEP_DEEP` | 原路由 |
| `/admin/prompts` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/permission/access` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/permission/policies` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/users` | `KEEP_VISIBLE` | `REDIRECT` | `/admin/permission/organization?tab=users` |
| `/admin/roles` | `KEEP_VISIBLE` | `REDIRECT` | `/admin/permission/organization?tab=roles` |
| `/admin/departments` | `KEEP_VISIBLE` | `REDIRECT` | `/admin/permission/organization?tab=departments` |
| `/admin/audit/logs` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/audit/slow-queries` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/audit/data-lineage` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| `/admin/audit/lineage` | 保持现有重定向 | `REDIRECT` | `/admin/audit/data-lineage` |
| `/admin/audit/lineage-graph` | 保持现有重定向 | `REDIRECT` | `/admin/audit/data-lineage` |
| `/admin/system/health` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由；业务归属改为运营与审计 |
| `/admin/system/operation-logs` | `KEEP_VISIBLE` | `MERGE_TARGET` | V2 暂时保留 URL；业务归属改为运营与审计 |
| `/admin/system/ai-config` | `KEEP_VISIBLE` | `MERGE_TARGET` | 原路由 |
| 访问审批前端路由 | `PLANNED` | `PLANNED` | 功能单独立项后使用 `/admin/permission/approvals` |
| 告警规则与历史前端路由 | `PLANNED` | `PLANNED` | 执行闭环完成后进入运行监控 Tab |

## 10. 菜单权限指导

菜单权限只决定入口是否可见，不能代替路由权限、接口权限和操作权限。

| 业务域 | 当前可复用权限 | 后续建议 |
| --- | --- | --- |
| 数据资产 | `datasource:manage`、`metadata:manage` | 按查看、采集、发布动作继续细分时保持兼容 |
| 数据治理 | `metadata:manage`、`field-tag:manage`、`feedback:review` | 分类标签、问题处理、审核动作分开 |
| 语义中心 | `knowledge:manage`、`prompt:manage`、`metadata:manage` | 术语、知识和 Prompt 审核可分离 |
| 权限中心 | `security:manage`、`user:manage`、`role:view`、`role:manage`、`department:manage` | 访问审批建议独立 view/review 权限 |
| 运营与审计 | `audit:view` | 操作日志、运行健康、告警管理按需要细分 |
| 平台设置 | `system:ai-config:view`、`system:ai-config:manage` | 保持查看与修改分离 |

必须遵守：

1. 一级业务域只要存在一个可访问的二级页面就可见。
2. 一级入口点击后跳转到第一个有权访问的二级页面。
3. 页面按钮继续按操作权限控制，不因菜单可见而默认可操作。
4. 路由守卫和后端接口必须再次校验权限。
5. 不在菜单重构阶段直接修改权限数据库；先稳定页面职责和操作清单。

## 11. 实施顺序

### 阶段一：V1 导航归一

1. 修改 `AppShell.vue` 的一级名称、分组和二级导航。
2. 按本文 5.2 和 5.3 节写入唯一的 `key`、`to`、`match` 和权限。
3. 统一 `router/index.ts` 的 `meta.section`、`meta.title`、`domainKey`、`workspaceKey` 和 `contextMode`。
4. 将操作日志的业务归属调整到运营与审计，URL 暂不修改。
5. 隐藏详情、编辑、审核、版本、差异和调度等深层页面的独立导航入口。
6. 按 5.3 节补齐深层页面的页内操作入口。
7. 改造上下文条和面包屑读取路由元数据。
8. 保留全部现有路由、后端接口和权限码。

V1 文件范围：

| 文件 | 修改内容 |
| --- | --- |
| `frontend/src/components/AppShell.vue` | 一级菜单、二级导航、匹配范围、默认可访问入口、路由元数据驱动的上下文显示 |
| `frontend/src/router/index.ts` | 统一标题、业务域、工作区、上下文模式和面包屑父级 |
| `frontend/src/components/AdminContextBar.vue` | 按 `contextMode` 渲染选择器，处理锁定对象和空状态 |
| `frontend/src/components/AdminBreadcrumb.vue` | 按 `domainKey`、`workspaceKey`、`breadcrumbParent` 生成层级 |
| `frontend/src/views/admin/metadata/CatalogSearch.vue` | 增加表浏览器辅助入口 |
| `frontend/src/views/admin/metadata/SyncTask.vue` | 增加同步调度入口 |
| `frontend/src/views/admin/metadata/SnapshotLifecycle.vue` | 增加快照列表、差异比较和版本历史入口 |
| `frontend/src/views/admin/knowledge/KnowledgeDashboard.vue` | 增加审核队列入口；保留编辑和版本入口 |

V1 不修改 `backend/`、`python-service/` 和数据库迁移。

### 阶段二：V2 工作区整合

1. 按 7.4 节整合数据目录与表浏览器。
2. 按 7.4 节整合快照、版本、差异和发布页面。
3. 按 7.4 节整合字段可信度与反馈审核。
4. 新增 `OrganizationWorkspace.vue`，整合用户、角色和部门。
5. 将语义知识审核整合到 `KnowledgeDashboard.vue`，编辑和版本继续作为深层页面。
6. 按第 9 节的 V2 唯一目标设置旧 URL 重定向。
7. 将 V1 的临时二级导航收敛为第 5.1 节最终菜单树。

V2 文件范围：

| 工作区 | 主要文件 |
| --- | --- |
| 数据目录 | `CatalogSearch.vue`、`TableExplorer.vue` |
| 版本与发布 | `SnapshotLifecycle.vue`、`SnapshotList.vue`、`VersionHistory.vue`、`SnapshotDiff.vue` |
| 可信度与反馈 | `ConfidenceDashboard.vue`、`FeedbackReview.vue` |
| 组织与角色 | 新增 `permission/OrganizationWorkspace.vue`，复用 `UserList.vue`、`RoleList.vue`、`DepartmentTree.vue` |
| 语义知识 | `KnowledgeDashboard.vue`、`ReviewPage.vue`、`SkillsEditor.vue`、`VersionList.vue` |
| 路由与导航 | `router/index.ts`、`AppShell.vue` |

### 阶段三：独立功能补齐

以下内容分别立项，不与 V1 或 V2 捆绑：

1. 访问审批前端入口和审批队列。
2. 告警执行、恢复、去重、历史和运行监控告警 Tab。
3. classification + tag 与现有字段标签页面对齐。
4. 稳定页面和动作后的权限码细分。

### 阶段四：验证

1. 用超级管理员、数据源管理员、治理人员、权限管理员和只读审计角色分别验证菜单。
2. 验证直接访问深层 URL、刷新、返回和面包屑。
3. 验证一级入口始终进入第一个可访问的二级页面。
4. 验证上下文切换不会残留其他数据源的快照或知识文档。
5. 验证不可问数原因能跳到正确处理页面。
6. 执行前端生产构建，并为可见改动保留验证截图。

### 11.1 V1 完成定义

- 一级菜单、二级导航与 5.2、5.3 节完全一致。
- 所有后台路由都有唯一 `domainKey` 和 `workspaceKey`。
- 所有页面的 `section` 使用本文统一业务域名称。
- 被移出二级导航的页面都有明确页内入口或重定向。
- 所有现有 URL、页面能力、后端接口和权限码保持兼容。
- 不同权限角色点击一级菜单时进入第一个可访问的二级页面。
- 上下文条不会显示与当前任务无关的选择器。
- 深层页面面包屑可以返回真实父页面。
- 智能问数页面无改动。
- `npm run build` 通过，并保存用户可见改动截图。

### 11.2 V2 完成定义

- 五个工作区按 7.4 节完成整合。
- 被合并页面的现有能力没有丢失。
- 第 9 节标记为 `REDIRECT` 的旧路由全部指向唯一目标。
- 页面 Tab 写入 URL 查询参数，刷新和浏览器前进后退可以恢复状态。
- 不存在无法从菜单、列表、详情或操作按钮到达的孤立路由。
- 数据源切换后不残留其他数据源的快照、知识或筛选状态。
- 权限不足时不显示入口，直接访问仍由路由守卫和后端拒绝。
- `npm run build` 通过，并完成核心角色的导航回归验证。

## 12. 验收清单

- 一级侧边栏只包含稳定业务域，没有表、字段、角色、快照等技术对象平铺。
- 所有后台路由都能归属到唯一业务域。
- 同一业务域在侧边栏、页面标题、面包屑和路由元数据中名称一致。
- 每个二级入口对应一个清晰用户任务，而不是一个后端对象。
- 详情、编辑、审核、版本、差异页面可以到达，但不会挤占稳定导航。
- 数据源详情能说明当前阶段、已完成步骤、首个阻断原因、责任角色和下一步。
- 快照发布与知识发布在界面上明确区分。
- 数据权限、行策略、列策略和脱敏策略都归入权限中心，资产和数据源详情只展示摘要。
- 查询审计与操作日志语义分开，但都归入运营与审计。
- 未完成的访问审批和告警闭环不会被菜单伪装成已交付能力。
- 智能问数页面不因本次后台菜单重构而改变。

## 13. 后续文档维护

本指导确认后，应把它作为后台菜单改造的当前目标，并同步处理以下文档关系：

1. `docs/development/completed/后台信息架构与导航规范.md` 保留为历史实现记录，不继续作为新目标规范。
2. `DataOcean-现有功能盘点与业务流程及菜单重构任务.md` 保留为分析任务说明。
3. 菜单代码落地后，在本指导中更新“当前路由迁移指导”和实际完成状态。
4. 页面职责、权限码或业务状态发生真实变化时，再同步更新 `AGENTS.md`、`CLAUDE.md` 和相关开发文档。
