# 005 元数据版本与审核

> 现行模块合同更新于 2026-11-07。当前运行环境与验收记录见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 修改定位

审核与发布的写入条件、权限和验证范围见下文。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 发布与撤回 | [SnapshotPublishServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotPublishServiceImpl.java)：`publishSnapshot`、`revokeSnapshot` | 旧正式版、状态迁移与审计。 |
| 门禁展示与强制校验 | [SnapshotReleaseGateServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotReleaseGateServiceImpl.java)：`evaluate`、`assertApprovable`、`assertPublishable` | 来源阻断与语义警告共用判定。 |

SnapshotVersionController → 发布服务 → 门禁 → 状态写入与事件；门禁必须在发布写副作用之前执行。页面改动同时核对 ReleasesView 与 ReleaseGatePanel。

事务回归覆盖来源检查先提交阻断、两个候选竞争同一正式指针及撤回。门禁依据和组件展示各有单独测试。[并发与指针测试](../../backend/DataOcean/src/test/java/com/dataocean/module/versioning/service/impl/SnapshotReleaseConcurrencyTest.java)、[门禁测试](../../backend/DataOcean/src/test/java/com/dataocean/module/versioning/service/impl/SnapshotReleaseGateServiceImplTest.java)、[展示测试](../../frontend/src/components/admin/ReleaseGatePanel.test.ts)。

## 概览与当前状态机

Java 管理快照的质量阶段、审核、发布、过期、撤回、版本历史与审计。只有合规版本才能作为知识事实来源；快照发布不是 RAG 活动 build 切换。

[SnapshotStatus](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/enums/SnapshotStatus.java)定义：

```text
DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED
                 └ 无问题时 CHECKING → APPROVED
PUBLISHED → APPROVED（撤回分支）
```

人工批准写入实际审核人及审核时间。正式快照进入 EXPIRED 由同来源的发布替换驱动，撤回则将当前正式快照转回 APPROVED。通用审核接口拒绝变更已发布快照。

版本页将来源质量检查和人工审核分开：`DRAFT` 的“进入审核”以 `metadata:release:review` 调用既有状态接口进入 `CHECKING`，`CHECKING/ISSUE_FOUND` 的批准仍读取审核门禁；质量检查权限不能替代审核权限。检查响应按实际 `complete/status/requestedScope` 与执行计数展示，`PARTIAL`、失败和不适用不报“校验完成”或把空分数当成零分。部分检查不自动迁移快照，人工审核和最终发布仍走原状态机与服务端门禁。

## 发布边界

版本页的通用准备流程默认收起，可键盘展开；当前正式版、逐行状态、门禁与发布/撤回影响继续直接显示。教学折叠不触发读取、批准、发布或构建，确认取消也不发送写请求。

- 同来源正式版本的过期、新快照的发布和相应审计在同一事务提交。撤回要求当前正式指针仍指向目标快照。
- 发布提交后，事件监听器发送通知并尝试全量来源质量检查。完整覆盖会自动关闭同源历史快照未解决的来源问题并推进问题版本。原问题 ID、批次和人工分派保留。知识文档发布与 RAG 构建继续使用各自的授权入口。
- 状态、来源门禁或正式指针发生冲突时返回 409。用户重新核对后决定是否再次操作。
- 已绑定的知识版本和进行中查询保留来源与依赖；旧 build 清理须等待引用结束。

### 审核、发布与撤回的请求条件

以下路径均位于 `/api/admin/snapshots/{snapshotId}`。账号必须在目标快照的实际数据源上具备相应 S1 能力。调用者在确认动作时固定源、快照、原状态及涉及的正式指针。

| 动作 | 请求 | 能力 | 写入与冲突处理 |
| --- | --- | --- | --- |
| 进入审核、记录问题、批准 | `PATCH /status`，传 `expectedStatus`、`targetStatus`，可传 `reason` | `metadata:release:review` | 仅允许状态机中的审核流转，原状态须匹配。进入 APPROVED 时重算审核门禁。 |
| 发布 | `POST /publish`，传 `expectedStatus:"APPROVED"`、`expectedPublishedSnapshotId` | `metadata:release:publish` | 读取同源正式指针时，有正式版传其 ID，无正式版显式传 null。遗漏字段返回 400。门禁和指针均通过才替换。 |
| 撤回 | `POST /revoke`，传 `expectedStatus:"PUBLISHED"`、`expectedPublishedSnapshotId`、`reason` | `metadata:release:publish` | 预期正式 ID 须等于目标快照，原因必填。成功后该源暂无正式快照。 |

服务端使用应用库 `datasource.id` 行串行处理同源正式指针和门禁依据。审核、发布和撤回在 READ_COMMITTED 事务中先取得来源锁，再读取当前快照、门禁及正式指针。状态更新的 SQL 同时带原状态条件。来源检查在执行器结束后、写入来源问题和可信结果前取得同一把锁。来源 HIGH 问题的人工流转和可用表状态修改也取得该锁，多源批量问题流转按数据源 ID 升序加锁。不同来源分别裁决。

发布预览显示 PASS 后，来源检查仍可先提交新的阻断问题。此时发布请求即使仍持有正确的原状态与正式 ID，也会因锁内门禁返回 409，原正式指针和发布审计保持原值。发布提交后启动的全量检查另有批次和覆盖结果。

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
- 查询接口 `GET /api/admin/snapshots/{snapshotId}/release-gate` 使用 `metadata:release:view` 和快照归属校验。响应包含 `approvable/publishable/blockingCount/warningCount/items`、来源 HIGH 的三种未解决状态计数 `unresolvedSourceHighByStatus`，以及该快照最近登记的实际检查批次 `latestCheckBatchId`。批次定位排除未开始执行的墓碑。问题入口使用各非零状态、HIGH、SOURCE 和当前源/快照精确筛选，检查入口读取指定批次。两个入口分别核对问题查看权和治理查看权。
- 前端只在单个快照的详情弹窗、发布确认和快照详情页读取门禁，不为版本列表逐行请求；阻断时禁用确认，只有语义 `WARN` 时允许发布并明确提示不阻断。

## 入口

| 代码 | 用途 |
| --- | --- |
| [SnapshotVersionController](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java) | `/api/admin/snapshots/{snapshotId}`下 status、publish、revoke、release-gate、audit-logs、diff |
| [SnapshotReleaseGateService](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/SnapshotReleaseGateService.java) | 审核/发布门禁的单一判定 |
| [versioning/service](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service) | 生命周期、发布、审计 |
| [versioning/event](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/event) | 发布/过期事件 |
| [ReleasesView](../../frontend/src/views/admin/releases/ReleasesView.vue)、[ReleaseGatePanel](../../frontend/src/components/admin/ReleaseGatePanel.vue) | 正式版本发布工作区与门禁清单 |
| [versioning API](../../frontend/src/api/admin/versioning.ts) | 版本、审核与门禁请求 |

数据源 version-history/published-snapshot/audit-logs 等接口以 Controller 为准；页面从 ReleasesView 进入。

## 页面数据语义

`version-history` 以数据源范围查询所有快照状态，没有候选状态过滤。记录表达快照及当前状态，不是操作事件；`createdAt` 是采集时间，`publishedAt` 与 `expiredAt` 分别是接口提供的发布时间和过期时间。`reviewedBy` 是审校人；没有发布人字段时，不能把它标为发布人。审计操作记录来自 `audit-logs` 的真实记录字段 `action/operatorName/reason/createdAt`。

`published-snapshot` 返回当前正式版快照及 `publishedAt`，但当前 VO 未提供发布人；前端摘要因此只显示真实版本号、状态身份和发布时间。状态 `PUBLISHED` 与“当前正式版”身份分开表达，后者按接口返回的 `snapshotId` 判断。`qualityScore` 是真实分值；接口没有评分说明字段，前端不补造解释，也不把分值当发布许可。

工作区以单个版本列表承载全部快照状态。手选比较保留基准与对比版本的顺序，“与当前正式版比较”使用 `published-snapshot.snapshotId` 作为基准。版本域 `GET /snapshots/{oldSnapshotId}/diff/{newSnapshotId}` 在两侧快照授权通过后核验同源，差异结果带 `datasourceId`、两侧快照 ID 与版本号。仅有版本查看权的用户可直接核对比较方向。

列表版本号和操作记录入口共用一个详情弹窗；关闭时保留列表上下文、选择和滚动。弹窗及独立详情路由复用同一快照详情读取状态与内容组件。`getSnapshotDetail` 返回该快照的 `tables` 和 `columns`；字段结构通过 `column.tableMetaId === table.id` 展开，按 `ordinalPosition` 排序，不需新增接口。操作记录来自真实审计接口，空记录和请求错误分开，不能根据当前状态构造历史事件。撤回仍由 `PUBLISHED` 状态、`metadata:release:publish` 能力、原因确认和原执行流程控制。治理查看用 `governance:view`；问题处理入口仅在精确快照查询到未解决问题且具备对应 IAM 能力时显示。以上为前端产品/交互规则，不改变后端状态机与权限模型。

## 修改与验证

检查允许与拒绝的状态迁移、审核人/操作者、重复发布、并发冲突、撤回和跨源拒绝。快照变化后还需核对[知识文档](006-knowledge.md)和[RAG](009-schema-rag.md)的来源绑定，不能仅凭快照状态宣称问数已就绪。

## 当前前端保护

| 入口／动作 | 范围与呈现 |
| --- | --- |
| `/admin/releases` | 全状态服务端分页及当前正式版摘要；正式版按published-snapshot精确ID，不能取最大版本号。 |
| 手选比较／与正式版比较 | 先选基准、仅同源，跳既有差异路由，保留新增/删除表字段、类型及注释变化。 |
| 版本详情／操作记录 | 列表弹窗和独立详情均保留；结构metadata:view与审计/门禁release:view独立，缺结构权不隐藏合法审计。历史来自真实audit，不按状态拼事件。 |
| 检查／审核／批准／发布／撤回 | 检查与审核能力分开，PARTIAL不报完整；批准不等于发布。发布独立确认，FAIL阻断、WARN说明不阻断；门禁读取失败交服务端最终裁决。撤回须原因，409保输入。进入知识准备不表示已生成/发布文档或索引。 |

版本页以采集核对、人工审核、快照发布、知识准备的四步解释职责；知识准备仅导航，不触发生成或索引。结构、发布门禁、审计分别核对真实读取能力，最小release:view账号不读取metadata结构。PUBLISHED/EXPIRED门禁摘要说明当前身份，不建议从这些状态再次发布。

版本动作在确认后重新核对目标、身份和能力。响应丢失时，版本工作区按账号、源、快照及原动作保留核对标识，重新读取当前快照事实和正式指针供用户判断。版本写接口没有操作 UUID，当前状态或同账号相同动作的审计无法单独确认是哪一次 HTTP 请求产生的结果。

本机隔离 MySQL 已验证最小角色的真实审核流转、陈旧条件、同源差异读取和先提交新阻断的竞争。成功替换及撤回通过真实生产服务在标记回滚的 MySQL 事务内读回，回滚后正式指针与审计恢复。另一次隔离 MySQL 全量检查因缺少适用的外键关系得到 PARTIAL，历史问题在事务内外均保留，新增问题和维度结果随主事务回滚。完整成功时自动关闭同源历史问题的分支经独立 H2 持久化回归覆盖。此次真实 HTTP 未提交成功发布，原正式快照、历史未解决问题及活动 RAG build 保留。[本轮验收](../archive/2026-11-07-全站配色与版本发布.md)。
