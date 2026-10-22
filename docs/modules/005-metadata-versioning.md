# 005 元数据版本与审核

> 文档类型：现行模块说明；核对日期：2026-10-22；元数据版本工作区实现已提交于 `0a908a3`，当前代码核对基线为 `65c609b`，本轮发布门禁与前端展示尚未提交。页面视觉仍待用户确认，运行验收见[项目现状](../项目现状.md)。

## 概览与当前状态机

Java 管理快照的质量阶段、审核、发布、过期、撤回、版本历史与审计。只有合规版本才能作为知识事实来源；快照发布不是 RAG 活动 build 切换。

[SnapshotStatus](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/enums/SnapshotStatus.java)定义：

```text
DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED
                 └ 无问题时 CHECKING → APPROVED
PUBLISHED → APPROVED（撤回分支）
```

具体条件由 Service 决定。旧说明中的 REVIEWING 不是当前该枚举状态，不能据此增加前端分支或数据库值。

## 发布边界

- 同一数据源发布新快照时处理旧发布版本，记录操作人与审计。
- 发布/过期事件同步实体和通知等现有流程；不将事件误写成文档自动向量化。
- 乐观锁冲突按现有 409 契约处理，不能静默覆盖。
- 已绑定的知识版本和进行中查询保留来源与依赖；旧 build 清理须等待引用结束。

## 发布门禁

审核与发布共用 [SnapshotReleaseGateService](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/SnapshotReleaseGateService.java) 的单一判定，避免页面显示与接口行为不一致。门禁固定返回三项：

| 检查项 | 规则 | 通过 | 未通过 | 阻断审核 | 阻断发布 |
| --- | --- | --- | --- | --- | --- |
| 来源高风险 | 快照内 `SOURCE + HIGH + OPEN/CONFIRMED/REOPENED` | PASS | FAIL | 是 | 是 |
| 可用表 | 快照内至少一张 `NORMAL/RECOMMENDED` 表 | PASS | FAIL | 否 | 是 |
| 语义高风险 | 快照内 `SEMANTIC + HIGH + OPEN/CONFIRMED/REOPENED` | PASS | WARN | 否 | 否 |

- 门禁失败统一返回 409；错误消息保留「无法发布：存在 N 个未解决的高风险问题」「无法发布：快照中无 NORMAL/RECOMMENDED 状态的表」等可识别文案。
- **发布接口必须执行门禁**：`SnapshotPublishServiceImpl#publishSnapshot` 在 `APPROVED` 状态校验之后、查询和过期旧发布版本之前调用 `assertPublishable`。门禁失败时不产生任何写副作用。
- 审核接口在目标状态为 `APPROVED` 时调用 `assertApprovable`；`CHECKING → APPROVED` 同样受来源高风险约束。
- 质量检查的完整检查自动状态分支在写入 `APPROVED` 前也会读取门禁，仍有来源高风险时落为 `ISSUE_FOUND`。
- 查询接口 `GET /api/admin/snapshots/{snapshotId}/release-gate` 使用 `metadata:release:view` 和快照归属校验，返回 `SnapshotReleaseGateVO`（`approvable/publishable/blockingCount/warningCount/items`）。
- 前端只在单个快照的详情弹窗、发布确认和快照详情页读取门禁，不为版本列表逐行请求；阻断时禁用确认，只有语义 `WARN` 时允许发布并明确提示不阻断。

## 入口

| 代码 | 用途 |
| --- | --- |
| [SnapshotVersionController](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java) | `/api/admin/snapshots/{snapshotId}`下 status、publish、revoke、release-gate、audit-logs、diff |
| [SnapshotReleaseGateService](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/SnapshotReleaseGateService.java) | 审核/发布门禁的单一判定 |
| [versioning/service](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/) | 生命周期、发布、审计 |
| [versioning/event](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/event/) | 发布/过期事件 |
| [ReleasesView](../../frontend/src/views/admin/releases/ReleasesView.vue)、[ReleaseGatePanel](../../frontend/src/components/admin/ReleaseGatePanel.vue) | 正式版本发布工作区与门禁清单 |
| [versioning API](../../frontend/src/api/admin/versioning.ts) | 版本、审核与门禁请求 |

数据源 version-history/published-snapshot/audit-logs 等具体接口以 Controller 为准；旧独立 SnapshotLifecycle/VersionHistory 页面不再是正式导航入口。

## 页面数据语义

`version-history` 以数据源范围查询所有快照状态，没有候选状态过滤。记录表达快照及当前状态，不是操作事件；`createdAt` 是采集时间，`publishedAt` 与 `expiredAt` 分别是接口提供的发布时间和过期时间。`reviewedBy` 是审校人；没有发布人字段时，不能把它标为发布人。审计操作记录来自 `audit-logs` 的真实记录字段 `action/operatorName/reason/createdAt`。

`published-snapshot` 返回当前正式版快照及 `publishedAt`，但当前 VO 未提供发布人；前端摘要因此只显示真实版本号、状态身份和发布时间。状态 `PUBLISHED` 与“当前正式版”身份分开表达，后者按接口返回的 `snapshotId` 判断。`qualityScore` 是真实分值；接口没有评分说明字段，前端不补造解释，也不把分值当发布许可。

工作区以单个版本列表承载 `version-history` 的全部状态；比较从列表发起。手选基准/对比两个版本时保留选择顺序；“与当前正式版比较”使用 `published-snapshot.snapshotId` 作为基准，差异仍跳转既有 `/admin/releases/snapshots/{id}/diff/{compareId}` 页面和 `compareVersions` API。差异页保留直接访问并核对两快照属于同一数据源。

列表版本号和操作记录入口共用一个详情弹窗；关闭时保留列表上下文、选择和滚动。弹窗及独立详情路由复用同一快照详情读取状态与内容组件。`getSnapshotDetail` 返回该快照的 `tables` 和 `columns`；字段结构通过 `column.tableMetaId === table.id` 展开，按 `ordinalPosition` 排序，不需新增接口。操作记录来自真实审计接口，空记录和请求错误分开，不能根据当前状态构造历史事件。撤回仍由 `PUBLISHED` 状态、`metadata:release:publish` 能力、原因确认和原执行流程控制。治理查看用 `governance:view`；问题处理入口仅在精确快照查询到未解决问题且具备对应 IAM 能力时显示。以上为前端产品/交互规则，不改变后端状态机与权限模型。

## 修改与验证

检查允许与拒绝的状态迁移、审核人/操作者、重复发布、并发冲突、撤回和跨源拒绝。快照变化后还需核对[知识文档](006-knowledge.md)和[RAG](009-schema-rag.md)的来源绑定，不能仅凭快照状态宣称问数已就绪。
