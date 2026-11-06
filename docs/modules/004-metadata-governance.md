# 004 元数据治理与可信度

> 状态：现行模块说明；整理日期：2026-11-07。实现与契约在本文维护，迁移/运行/验收结论只从[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)读取。

<a id="v3-治理分析总览"></a>

## 治理分析总览

`/admin/governance` 当前承担指定来源与明确快照的分析任务：问题集中度、来源/语义分域与独立校验覆盖，问题处理在专页完成。页面形态按[前端规范](../development/前端开发规范.md)和用户任务调整。总览入口继续使用治理查看能力；问题统计/明细、规则规划、元数据范围分别按既有功能与负责源读取，缺辅助权不补权。已有来源检查和完整执行依据放在按需展开区域，文档发布/显式RAG、语义正确性与来源质量分仍分开。

总量、单状态和分域来自权威 snapshot issue summary；表×状态聚合采用三个单状态的完整分页、ID去重/范围校验和二次摘要对账，有读取预算。任何不完整、错属、漂移或超预算均不提供总体排行；没有事务水位，不宣称原子快照。图形单状态直接跳问题中心，域/表汇总先选真实支持的单状态，不新增后端筛选。REOPENED显示“需重新处理”，表示此前结束的问题再次待确认，既可能人工重开，也可能语义复查触发；枚举和状态机不变。

校验覆盖以真实批次完整规则数为分母，执行成功不等于质量通过；缺 ruleCount/覆盖、有限历史、无批次、失败或无权不补0。停止聚合只影响前端读取/回填，不改变后端任务。当前实现与真实/Mock边界见[全站重设计验收](../acceptance/2026-11-06-全站重设计与基础整治.md)。

前端入口：[QualityDashboard.vue](../../frontend/src/views/admin/governance/QualityDashboard.vue)组织范围、权限、批次与跳转；[governanceOverview.ts](../../frontend/src/utils/governanceOverview.ts)核验摘要、完整分页和覆盖分母；[GovernanceOverviewCharts.vue](../../frontend/src/components/admin/GovernanceOverviewCharts.vue)展示图形与未知状态。聚合上限为 2000 条、24 次分页请求；超过预算保留权威摘要，不绘制部分表排行。对应回归入口为 [governanceOverview.test.ts](../../frontend/src/utils/governanceOverview.test.ts)、[GovernanceOverviewCharts.test.ts](../../frontend/src/components/admin/GovernanceOverviewCharts.test.ts)及 [GovernanceOverviewIssuesLifecycle.test.ts](../../frontend/src/views/admin/governance/GovernanceOverviewIssuesLifecycle.test.ts)。

### 指标与精确跳转合同

| 图形／数值 | 权威读取、范围与分母 | 跳转／降级 |
| --- | --- | --- |
| 未解决问题总数、状态与域 | 当前源＋快照`quality-issues/summary`；唯一问题ID的OPEN＋CONFIRMED＋REOPENED，SOURCE/SEMANTIC为同集合互斥分域；均需issue:view | 真0才显示0；缺字段、合计不符或拒绝不推算。摘要的两个边际分布不能补造域×状态数量。 |
| 表×处理状态集中度／涉及表数 | 同快照按三个单状态完整分页，去重、核验归属及二次摘要对账；最多2000条、24次明细请求，每页100条 | 不完整/漂移/超预算隐藏总体表排行和涉及表数，保留有效摘要；无表名另列，不当真实表名筛选。前8组外明确未绘问题数，非事务快照。 |
| 来源校验执行覆盖 | 最新`quality-check-batches/latest`的实际rules及匹配ruleCount；分母是该批规则数，需governance:view | SUCCESS是执行成功，不是质量通过。无批次、有限历史、失败、缺分母和PARTIAL分别呈现；complete=false不展示本次完整评分。 |
| 图形到问题中心 | 目标只支持单个status及已有tableName/issueDomain；同datasourceId＋snapshotId | 色段可精确单状态跳转；表或域总量先选单状态，不发送UNRESOLVED并集或声称默认全部状态等于未解决。辅助能力与目标页仍各自校验。 |

没有权威时间序列、全模块对象状态/待审积压或统一分母时，不绘趋势、SLA或跨审核/发布/RAG完成率。总览分析情况，具体问题处理在专页；覆盖带没有精确规则执行筛选时，只展开已有执行依据。

## 配套功能定位

当前治理内页的路由、对象与交互合同见下方[字段治理前端工作面](#字段治理前端工作面)；已完成的阶段映射不再作为开发计划单独维护。

复杂流程继续读：[治理任务与业务知识](governance/治理任务与业务知识.md)、[反馈归因与使用档案](governance/反馈归因与使用档案.md)。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 质量规则分页、修订、复制与恢复 | [QualityRuleServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityRuleServiceImpl.java)：`listRules`、`createRule`、`updateRule`、`copyRule`、`history`、`restoreRule` | 规则启用不代表已执行。 |
| 规则操作回执、重复提交与未知结果恢复 | [QualityRuleOperationServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityRuleOperationServiceImpl.java)：`execute`、`get`、`resolve` | 操作归属、原版本、持久终结与当前范围授权。 |
| 字段分类标签 | [FieldTagServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/service/impl/FieldTagServiceImpl.java)：`addTag`、`batchAddTags`、`removeTag`、`getTagsByColumnMetaId` | 标签来源和字段范围。 |
| 旧来源可信度 | [FieldConfidenceServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/service/impl/FieldConfidenceServiceImpl.java)：`pageConfidence`、`getConfidence`、`adminSetScore`、`getEventHistory` | 旧来源分不等于新版使用参考分。 |

新业务知识与一键治理见[治理任务](governance/治理任务与业务知识.md)；新版本使用参考分与反馈结案见[反馈归因](governance/反馈归因与使用档案.md)。旧来源可信度变更不能覆盖这两条新链路。

验证入口：[QualityRuleServicePersistenceTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/governance/service/impl/QualityRuleServicePersistenceTest.java)（规则持久化）、[QualityRuleOperationPersistenceTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/governance/service/impl/QualityRuleOperationPersistenceTest.java)（操作回执、事务与并发）、[FieldGovernanceWriteAndListTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/fieldtag/service/impl/FieldGovernanceWriteAndListTest.java)（标签与范围）、[ConfidenceCalculatorImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/fieldtag/service/impl/ConfidenceCalculatorImplTest.java)（来源可信度计算）。规则本轮实际测试与环境边界见[可靠写入验收](../acceptance/2026-11-07-质量规则可靠写入.md)，不外推标签或可信度验收。

## 修改定位

先按修改意图定位符号，再阅读下方行为约束。源码定位核对日期：2026-11-01；不代表运行验收。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 来源质量检查与规则摘要 | [QualityCheckServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityCheckServiceImpl.java)：`executeQualityCheck`、`ruleSummary` | SOURCE 评分与独立 SEMANTIC 证据。 |
| 问题处理、批量与分派 | [QualityIssueServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImpl.java)：`handleIssue`、`batchHandle`、`assignIssue` | 状态并发与部分成功。 |
| 字段解释草稿、候选与审核 | [FieldSemanticServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServiceImpl.java)：`saveDraft`、`generateCandidate`、`adoptCandidate`、`submit`、`review`、`evaluate` | 来源绑定、修订与复查。 |

来源检查与字段语义是两条链，不能用来源分代表语义通过。字段事实进入文档后继续读[术语与 AI 释义](knowledge/业务术语与AI释义.md)，发布门禁转到版本模块。

验证入口：[QualityCheckServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/governance/service/impl/QualityCheckServiceImplTest.java)（来源检查）、[QualityIssueServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImplTest.java)（问题处理）、[FieldSemanticServicePersistenceTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServicePersistenceTest.java)（字段解释）。本次只核对测试入口，修改实现后按[开发指南](../development/本地开发与验证.md)运行相关测试，再按影响范围补充接口或页面验证。

## 概览与主流程

平台内任务、业务知识修订、多篇文档、查询期档案及反馈回流已接通；实现约束在本文与专题维护，实际运行覆盖见[验收索引](../acceptance/README.md)。

主线负责知识缺口发现、AI/人工治理、修订审核与复查、标签及反馈再治理，同时保留独立的来源质量检查、问题处理和对象可用状态维护。字段业务解释由语义中心承载，脱敏候选由授权配置的字段保护承载；治理域不再重复这两个入口。

“数据质量”当前包含来源检查（结构和受控只读数据诊断）及独立语义复查两类证据。来源评分不等于业务口径正确率，语义问题不混入来源分；零问题记录也不代表完成检查。治理总览已有历史评分、可核验批次及覆盖的分别展示。RAG/问数按精确有效知识和当前 IAM 消费，血缘拓扑按实际来源维护；下游分别遵守[RAG](009-schema-rag.md)、[问数](008-query-memory.md)和[查询追溯](011-audit-lineage.md)合同。

选定源与快照 → 发现知识缺口 → AI 候选/人工补充 → 审核及精确修订复查 → 文档审核发布 → 明确确认 RAG → 实际使用与反馈归因 → 再治理及原情景人工验收。来源质量问题保持独立生命周期。

## 平台内字段解释

当前前端将原始来源/当前有效解释与候选、人工草稿、审核和历史分开。任务页保留五个Tab及源级反馈入口，快照必需与源级工作面分开校验。字段目录读取仍需要metadata:view，无该能力明确说明，未从治理查看能力补权。表单与审核各自固定乐观版本和精确修订；晚AI/保存重读不得覆盖人工输入，409保留依据，批量来源确认逐项展示结果。页面定位及保护详见[治理任务专题](governance/治理任务与业务知识.md)，实际合成浏览器覆盖见[第四阶段验收](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d4)。

字段治理阶段：字段工作面新增服务端阶段提示，`GET /api/admin/field-semantics/columns/{columnMetaId}/governance-progress?snapshotId=...` 按现有字段查看权限与真实来源返回待治理、人工补充、待审核、待复查、不适用或本轮完成。仅当前 definition/binding/revision/review/evidenceHash 与规则版本均匹配、且同源同快照批次成功时返回本轮完成；新草稿和待审核修订优先展示。此阶段接口复用已有语义证据；批量任务另有独立治理任务记录，阶段读取不等于文档或 RAG 生效。

用户明确要求在平台内帮助理解字段，并保存可审核、可复用的业务解释。`CompletenessChecker` 继续检查原始采集注释。字段治理默认进入治理任务，另有业务知识、标签、使用可信度和反馈入口；字段业务解释编辑仍复用「语义中心 → 字段解释」（`/admin/semantics/field-explanations`），脱敏候选位于「授权配置 → 字段保护」。平台解释不回写源注释，也不将原注释来源问题伪造成已解决。

### 知识治理任务与来源质量规则

业务知识的依据引用/摘录由人工明确确认并绑定当前来源与内容，AI 不得覆盖原始资料或自证；新的字段审核通过同样必须记录原始资料位置和人工核对。负向反馈先固定复现情景和预期结果，知识复查仅进入待验证，原问题经独立人工验收后结束。治理执行领取令牌保护最终写入，暂停会使晚到结果失效；一键与重试预算明确限制。使用参考分改为政策版本 2 的规范化 SQL 样例，旧事件留档，不自动重算为新证据。

知识治理提供内置缺口规则目录，检查解释、角色分类、金额单位、枚举含义及审核/复查证据。发现操作产生当前源和快照的对象任务；AI 仅生成候选，人工保存、提交审核后按精确修订复查。已有人工草稿不会被批量 AI 覆盖；未完成来源绑定转人工，逐项失败可以重试，过期运行任务显示失败。`DISCOVERED` 可进入治理，但不会因此取得问数资格。

来源质量规则仍配置具体检查项、对象范围和阈值。检查项是后端可执行能力，规则是使用该能力的配置；它们处理结构及只读数据诊断，不替代业务知识审核。语义规则版本为 2；标签角色变化会使旧复查证据失效，动态评分变化不会。

数据源用途、表粒度、关系与指标由 `BusinessKnowledgeServiceImpl` 管理独立修订。提交需要完整依赖和业务证据；关系必须明确连接条件、基数与聚合风险。人工确认、复查后成为有效事实；不适用须保留原因，不进入检索。新草稿不自动撤销旧有效版，旧有效版可独立撤销。保存采用版本比较，来源变化需查看原始/当前来源并明确确认，晚到 AI 不能覆盖新人工版本。审核、复查、撤销及反馈流转保留同事务审计。

工作台使用 `/api/admin/governance-tasks`、`/api/admin/business-knowledge`、`/api/admin/governance-feedback` 和 `/api/admin/field-profiles`，查询列表采用服务端分页，权限始终由当前源的 S1 能力判断。来源、修订或权限变化按对象提示，不把单项失效伪装为整批完成。

字段语义使用独立主记录、来源绑定、修订/AI 候选/审核及 SEMANTIC 复查批次。来源规则、评分、快照状态与 HIGH 门禁限定 SOURCE；独立审核和版本证据不能由历史方案代替。剩余实测见[后续开发](../development/后续开发.md#真实业务闭环)。

## 字段治理前端工作面

字段治理`/admin/governance/fields`与独立业务知识页复用相同事实和修订。字段解释在`/admin/semantics/field-explanations`，采用当前解释优先的纵向阅读；旧“左侧解释＋默认修订表单”布局已被替代。具体阅读/编辑组织见[前端规范](../development/前端开发规范.md#先读的边界)。

| 工作面／组件 | 保留能力与对象边界 |
| --- | --- |
| `tasks`／`GovernanceWorklist.vue` | 后端分页/总数、队列与运行/失败、轮询、发现、人工接管、暂停、重试和复查。发现组合核验check、field:manage及knowledge:manage/view；业务任务重试另需knowledge:manage，查看不要求维护权。 |
| `business`／`BusinessKnowledgeWorkbench.vue` | 用途、粒度、关系、指标四类事实；依赖、资料引用/确认、sourceHash、修订历史、提交/审核/复查/撤回/不适用及生成主题文档。阅读、当前有效版和新修订分开；复查不自动发布或build。 |
| `tags`／`FieldTagManager.vue` | 精确快照字段、预定义标签、批量新增与模式打标；批量冻结IDs，全源模式覆盖所有采集版本且单独确认。没有新增标签删除API。 |
| `confidence`／`GovernanceUsageProfiles.vue` | 当前语义修订档案、标签/知识资格、参考分和证据分页；证据核对revisionId，部分失败不补0，去重使用不等于正确概率。 |
| 同Tab按需旧面板／`ConfidenceDashboard.vue` | 独立源级旧政策、等级/全量计数、趋势和人工设置；不因缺快照隐藏，不改变policy2或自动奖励/衰减。 |
| `feedback`／`GovernanceFeedbackQueue.vue` | 源级归因、实际targets/原语义修订、原因及固定原情景/预期、修复revision人工核验和历史；不增加快照或metadata:view门禁，不自动修订知识。 |
| 同Tab按需旧面板／`FeedbackReview.vue` | 原全授权源合并列表、通过/驳回；DTO无逐行source不伪造公共源过滤，行授权仍由服务端检查，无客户端CAS的风险不以界面锁宣称解决。 |
| 字段解释／`FieldSemanticWorkspace.vue` | 原注释、有效解释、草稿、AI候选、来源确认、术语、审核/撤回/撤销、同修订复查、收录及历史；批量来源确认逐项固定expectedVersion、部分失败保留，不能自动采纳候选。 |
| `/admin/semantics/glossaries`／`GlossariesView.vue`等 | 分组、定义/同义词、映射候选分页、草稿保存/提交、审核/退回/删除及历史快照；BROKEN不降为未绑定，全fresh修订定义与映射一致，409保稿。详细术语合同见[知识模块](006-knowledge.md)。 |

字段basic/progress按field:view；details/usage另需knowledge:view；术语选项为field:view＋glossary:view，不额外借knowledge:view。保存/AI/采纳/提交/撤回需要field:manage＋knowledge:manage，审核为field:view＋knowledge:approve，撤销为field:manage＋knowledge:approve，语义复查为check。各码对目标来源分别满足真实绑定，不能拼接角色功能与其他绑定的负责源。

公共快照及表/字段目录仍需要metadata:view；缺辅助权明确说明，不扩权，源级反馈与旧记录仍可用。草稿脏输入不能提交；晚AI不覆盖输入；保存基准版本与审核revision/version分开固定；409保留依据，不换新版本重发。明确替换脏草稿须确认，未知写锁只在当前SPA/身份中，不能替代后端幂等、CAS或跨刷新恢复。历史失败与权限缺口证据见[阶段D4](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d4)、[阶段D7](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d7)。

## 关键边界

- 未解决问题的统一口径为 `OPEN/CONFIRMED/REOPENED`，定义在 `MetadataQualityIssue#UNRESOLVED_STATUSES`。发布门禁、数据源就绪度、工作台统计、质量分聚合和重新检查清理共用这一口径。
- 快照发布门禁由 [SnapshotReleaseGateService](005-metadata-versioning.md#发布门禁) 统一判定：来源高风险阻断审核和发布，语义高风险只提示、不阻断。治理检查仍不生成血缘。
- DISCOVERED、NORMAL、RECOMMENDED、DEPRECATED、BLOCKED 等治理状态按现有服务规则流转，不等同于审核状态。
- DEPRECATED/BLOCKED 表字段禁止进入检索、SQL 生成和临时访问批准；NORMAL/RECOMMENDED 本身也不能跳过来源快照、审核、build membership 或当前 IAM 检查。
- 来源质量分、既有来源可信度和新语义版本的使用参考分是不同口径，都不能代替权限。旧 `fieldtag` 来源可信度的可配置读时衰减（默认半衰期 30 天）仅属于折叠的既有记录；新使用参考分采用政策 2 的规范化 SQL 样例，不套用该衰减规则。
- 批量处理返回 `updated/skipped/skippedIssues`；跳过是正常部分成功结果，前端必须显示原因，不整体抛错或报全成功。
- 两个问题列表的 assigneeId 和目录的数据源范围均下推 SQL，禁止分页后再过滤。

## 代码与接口

| 代码 | 用途 |
| --- | --- |
| [MetadataGovernanceController](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java) | `/api/admin`下 quality-check、quality-rules、quality-issues、治理状态与 review-records |
| [governance/checker](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/checker) | 元数据维度与数据质量检查 |
| [governance/service](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service) | 问题、状态、审核与质量聚合 |
| [fieldtag](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag) | 标签及既有来源可信度/字段反馈；不作为新版本使用分的权威实现 |
| [GovernanceFieldProfileServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceFieldProfileServiceImpl.java) | 当前语义版本的独立使用档案、政策 2 样例与证据账本 |
| [GovernanceFeedbackServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceFeedbackServiceImpl.java) | 实际使用归因、再治理和原情景人工验收 |
| [QualityDashboard](../../frontend/src/views/admin/governance/QualityDashboard.vue)、[IssueList](../../frontend/src/views/admin/governance/IssueList.vue) | 正式质量与问题页面 |
| [RuleApplicabilitySummary](../../frontend/src/components/admin/RuleApplicabilitySummary.vue)、[QualityRuleApplicabilityVO](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/entity/vo/QualityRuleApplicabilityVO.java) | 当前快照的只读规划、来源和预计覆盖，不能作为实际执行结果 |
| [governance API](../../frontend/src/api/admin/governance.ts) | 请求 DTO、筛选与批量返回 |
| [FieldSemanticController](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/controller/FieldSemanticController.java)、[FieldSemanticServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServiceImpl.java) | 字段权威解释、版本并发、同源权限、复采绑定、审核与撤销 |
| [FieldSemanticBindingBatchController](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/controller/FieldSemanticBindingBatchController.java)、[FieldSemanticBindingBatchServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticBindingBatchServiceImpl.java) | 待确认来源清单、整批权限预检、逐项事务与部分失败结果 |
| [SemanticCheckExecutionService](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/SemanticCheckExecutionService.java)、[SemanticCheckController](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/controller/SemanticCheckController.java) | 独立 SEMANTIC 批次与字段问题处理证据 |
| [FieldSemanticWorkspace](../../frontend/src/views/admin/governance/FieldSemanticWorkspace.vue) | 沿用公共 ScopeBar 的字段解释、人工/AI 候选、审核与操作历史工作面 |

质量问题统一使用 `issueDomain=SOURCE/SEMANTIC`。全量 SOURCE 检查只清理 SOURCE 问题；语义检查单独写入批次/结果，不调用来源质量评分、快照状态或置信度联动。SEMANTIC 问题关闭需最新成功复查结果及当前字段/修订/审核/绑定 hash 一致；域筛选及分域统计由后端按当前权限范围计算。

复采后的来源绑定可逐字段确认，也可在语义中心的字段解释工作面按当前公共快照批量确认。批量请求先预校验所选字段真实来源及同一源上的 `governance:field:manage`、`knowledge:manage`，任一授权失败则整批不写；通过预检后每项独立事务和并发版本提交，并返回成功数及逐字段失败原因。结构或完整依据变化时绑定持久化为 `NEEDS_REVIEW`，不会被批量确认吞掉。

同快照来源变化后，显式保存新草稿会在锁定主记录、校验当前来源可用和归属之后，仅为指纹已变化的 `NEEDS_REVIEW` 绑定更新来源指纹；绑定仍需复核，新的草稿使用当前依赖 hash。已有批准修订和审核记录不改写，草稿或待审核阶段也不能使旧依据重新有效；只有新审核通过才将当前绑定置为 `VALID`。提交、审核和跨快照确认仍重核完整依据，`UNAVAILABLE` 不借保存草稿恢复；已有 `BIND_CONFIRM` 链还要求原审核绑定继续为 `VALID`，来源回滚不能复活旧 membership。

审核或确认使绑定成为 `VALID` 时显式清空当前 `status_reason`；工作面投影也不展示历史 `VALID` 行残留的失效原因。`NEEDS_REVIEW`、`UNAVAILABLE` 的原因和历史修订/审核证据保留，不批量清理历史数据。

## 修改与验证

质量检查必须针对实际选定源和快照，记录 SQL 异常和结果；不以脚本静态通过宣称真实质量闭环。检查部分成功、无权、无快照、失败与复查。发布前和读取时仍检查治理准入，历史正常状态不能覆盖当前禁止状态。

## 质量规则模板与实例

规则实例通过 `templateCode` 选择已注册执行器；实例 `ruleCode` 只是独立身份，不参与处理器分派。模板注册表只公开当前有执行器的 15 项能力：

- 完整性：`COMP_TABLE_COMMENT_MISSING`、`COMP_COLUMN_COMMENT_MISSING`、`COMP_PRIMARY_KEY_MISSING`、`DATA_NULL_RATE_HIGH`。
- 准确性：`ACCU_TYPE_NAME_MISMATCH`、`ACCU_ENUM_ANOMALY`、`DATA_UNIQUE_VIOLATION`。
- 一致性：`CONS_CROSS_TABLE_TYPE_MISMATCH`、`CONS_CROSS_TABLE_COMMENT_CONFLICT`、`DATA_FK_ORPHAN`。
- 时效性：`TIME_SNAPSHOT_EXPIRED`、`TIME_TABLE_NO_UPDATE`、`DATA_STALE_TABLE`。
- 可追溯性：`TRACE_FK_MISSING`、`TRACE_ISOLATED_TABLE`。

模板提供维度、目标类型、允许范围和类型化参数定义；空值率、枚举基数、快照年龄、数据陈旧天数有上下界，唯一性模板另有“仅检查主键”布尔参数。不开放 SQL、脚本或表达式。未知旧规则编码不映射模板，也不会进入检查；列表明确标成未映射/不支持，不能通过随意选择模板让它执行。

实例保存名称、模板、说明、严重程度、扣分、参数、启用状态、范围及配置版本。范围为全局、数据源、表或字段；表/字段目标由已选数据源真实元数据选择，并按数据源 ID 加稳定名称解析到后续快照，不绑定旧快照行 ID。对象缺失或来源不匹配记录为不适用/失败原因，不回退到其他对象。模板声明允许的组合由服务端再次校验。

同模板和目标按全局 < 数据源 < 表 < 字段选择唯一有效配置，覆盖后不对相同检查重复扣分；同级的启用冲突拒绝保存或启用。编辑、启停、删除和恢复都推进同一配置版本并记录变更人/时间；复制校验源版本，生成停用的 v1 新实例，不推进源版本。停用保留配置；删除在单次操作内停用并软删除，恢复只恢复为停用。规则变更写入 `metadata_quality_rule_revision`，执行批次保留当时规则配置快照，因此当前编辑/删除不改写历史依据。迁移时只为当时可读取的旧配置写 `BASELINE`，不伪造迁移前事件。

恢复会在数据库中明确清除删除时间和删除人，并保持停用。规则编辑按完整配置保存，作用范围变更会清空不再适用的数据源、表和字段；持久配置及新增修订取自同一份配置。写接口返回操作回执，读取完整配置仍使用规则详情接口。

### 规则写入合同

新前端通过 [qualityRuleOperations.ts](../../frontend/src/api/admin/qualityRuleOperations.ts) 调用以下 Java 接口。保存配置、启用和恢复都不执行质量检查。

| 接口 | 请求与结果 |
| --- | --- |
| `POST /api/admin/quality-rules/operations` | 所有动作必填 `operationId` UUID 与 `action`。`CREATE` 另带配置，不带对象/预期版本；`UPDATE` 另带完整配置及正整数 `ruleId/expectedVersion`；`COPY/ENABLE/DISABLE/DELETE/RESTORE` 另带对象/版本，不带配置。以上内容在提交前一起冻结。 |
| `GET /api/admin/quality-rules/operations/{operationId}` | 只读取当前账号的原操作回执；未发现已提交回执返回 `NOT_FOUND`，不据此判断在途请求已停止。 |
| `POST /api/admin/quality-rules/operations/{operationId}/resolve` | 与原提交争用同一账号/操作 ID 唯一键；原提交先完成则返回原回执，否则持久化拒绝记录，阻止后来到达的原请求写入。结束原操作不撤销已提交配置。 |

回执为 `operationId/action/outcome/ruleId/configVersion/errorCode/errorMessage`，不附完整规则正文。`APPLIED` 表示原配置操作已提交；`REJECTED` 表示明确拒绝；`CONFLICT` 表示版本或业务条件冲突。确定的业务结果通常以 HTTP 200 的 `data.outcome` 表达，不能只看 HTTP 成功；权限拒绝返回 HTTP 403，不向失权客户端提供对象版本。请求绑定/格式错误可直接返回 400，同操作 ID 改内容返回 409；这些 HTTP 拒绝与回执内的结果需分别处理。

服务端以当前账号和操作 ID 唯一约束去重，指纹包含账号、动作、对象、预期版本及完整配置。同 ID、同内容只返回原回执；同 ID、不同内容不覆盖原结果。写入先锁定规则实际原范围，再校验原/目标授权及客户端读取时的版本，实际 UPDATE 仍带 `config_version = expectedVersion` 条件；前端不再用保存前重读代替服务端原子检查。

[V76](../../backend/DataOcean/src/main/resources/db/migration/V76__quality_rule_operation_receipts.sql) 定义 `quality_rule_operation` 持久回执和 `quality_rule_write_guard` 单行领域锁。规则、修订与成功回执在同一事务提交；业务拒绝通过嵌套保存点回滚本次配置后记录拒绝，非业务持久化异常回滚整个事务。低频配置写先取得领域锁再读原规则，跨不同规则的同级启用冲突也受保护；列表、规划和实际检查不取得此锁。回执保存原/目标授权范围和结果标识，不保存草稿正文或凭据；查询、重放和终结均重新核验原范围、目标范围及对象当前范围。迁移实际执行环境、并发与故障实测仅以[本轮验收](../acceptance/2026-11-07-质量规则可靠写入.md)和[项目现状](../项目现状.md)为准。

旧写路径仍存在，但已严格适配同一操作服务，不能继续按旧载荷无条件调用：

| 旧路径（均以 `/api/admin` 开头） | 必填 query 参数 | 请求体 |
| --- | --- | --- |
| `POST /quality-rules` | `operationId` | 完整 `QualityRuleDTO` |
| `PUT /quality-rules/{ruleId}` | `operationId`、`expectedVersion` | 完整 `QualityRuleDTO` |
| `POST /quality-rules/{ruleId}/copy` | `operationId`、`expectedVersion` | 无 |
| `PATCH /quality-rules/{ruleId}/enabled` | `operationId`、`expectedVersion` | 必填布尔 `enabled` |
| `PATCH /quality-rules/{ruleId}` | `operationId`、`expectedVersion` | 必填布尔 `enabled`；原兼容入口同样受条件保护 |
| `DELETE /quality-rules/{ruleId}` | `operationId`、`expectedVersion` | 无 |
| `POST /quality-rules/{ruleId}/restore` | `operationId`、`expectedVersion` | 无 |

这些路径都返回 `Result<QualityRuleOperationVO>`，不再返回完整 `QualityRuleVO` 或空成功结果。缺参数直接拒绝，调用方必须迁移条件和回执解析；本轮前端已使用统一 operations 接口。

### 工作区职责与恢复

| 职责 | 主责文件 |
| --- | --- |
| 页面、编辑会话、目标目录及详情交互 | [QualityRulesView.vue](../../frontend/src/views/admin/governance/QualityRulesView.vue) |
| 身份、全局/数据源能力、配置范围与独立快照规划键 | [useQualityRuleScope.ts](../../frontend/src/views/admin/governance/rules/useQualityRuleScope.ts) |
| 列表分页筛选、检查项目录、只读快照规划及各自请求代次 | [useQualityRuleReads.ts](../../frontend/src/views/admin/governance/rules/useQualityRuleReads.ts) |
| 人工草稿、原配置版本、稳定草稿归属和定向清理 | [qualityRuleDrafts.ts](../../frontend/src/views/admin/governance/rules/qualityRuleDrafts.ts) |
| 提交互斥、最小恢复标识、回执核对和终结 | [useQualityRuleOperations.ts](../../frontend/src/views/admin/governance/rules/useQualityRuleOperations.ts) |

源级配置读取不要求公共快照或 `metadata:view`；表/字段目录单独判权，已有编辑工作面先打开，辅助目录延迟或失败不覆盖人工输入。全局定义、源级配置、快照规划、配置修订和实际执行历史仍是不同事实；详情主读取与历史辅助读取分开处理。

关闭编辑器、切换有权来源和 SPA 内返回保留内存草稿及原版本；整页刷新恢复的是操作关联，不承诺恢复未保存正文。新建草稿以打开时的稳定 `draftKey` 关联，表单切换全局/源级范围不改写该归属。409 保留原草稿，不自动换版本重发；能力暂时读取失败只隐藏待核验内容，确认撤权/范围失效后定向清理关联草稿和回执标识，旧源迟到 403 不删除后来有权源的草稿，身份结束清空内存草稿。

发请求前，按账号将操作 ID、动作、对象 ID、原/目标范围、时间、草稿定位键及必要的已提交结果 ID/版本写入 `localStorage`。不新增草稿正文、规则名称、参数、令牌或完整服务端配置的持久副本。恢复记录解析失败或无法读写时拒绝新提交；超时、网络错误、5xx 或不完整回执保留原 ID，不自动生成新操作重发。用户先核对原回执；`NOT_FOUND` 必须继续核对，或显式结束原操作并获得持久拒绝结果，才能释放原操作。

收到 `APPLIED` 与工作面处理完该成功结果是两步。提交期间又改稿、关闭或离开时，保留已提交 ID/版本与互斥；用户读取已保存配置、确认替换对应脏草稿后才解除。回读失败仍保留该操作，防止已创建成功的草稿再次按 `CREATE` 提交。定向回归入口为 [useQualityRuleOperations.test.ts](../../frontend/src/views/admin/governance/rules/useQualityRuleOperations.test.ts)及 [GovernanceRulesStatusLifecycle.test.ts](../../frontend/src/views/admin/governance/GovernanceRulesStatusLifecycle.test.ts)；真实角色、异常路径与未验证范围见[本轮验收](../acceptance/2026-11-07-质量规则可靠写入.md)。

## 批次、覆盖与可信结果

每次执行先固定启用规则与配置版本，创建 `quality_check_batch` 和逐规则 `quality_check_batch_rule` 覆盖记录。规则覆盖状态为 `SUCCESS`、`FAILURE`、`NOT_APPLICABLE`、`NOT_EXECUTED`，批次区分成功、部分失败、失败、运行中和仅有限可解释的旧结果。批次记录使用独立事务保存失败状态；检查器异常不再捕获后继续算分。没有适用规则、检查不完整或有失败时不生成“全部通过”结果，不按成功路径清理旧问题、更新分数、批准快照或覆盖此前可信完整结果。完整全量检查才按既有问题生命周期处理 OPEN/CONFIRMED；REOPENED 仍待处置。已发布快照保护和原有评分权重保留。

`DATA_FK_ORPHAN` 检查器接收执行计划筛选后的关系及其安全端点；检查器还会按表和字段治理状态再次拒绝受限关系。元数据类检查继续收到其所需的关系上下文。`DATA_STALE_TABLE` 的规划与执行共用候选字段选择和治理状态过滤；没有可读时间字段时记为不适用并阻止完整覆盖，有多个候选时只读取优先级最高的可用字段。修订历史按每条修订记录的数据源范围过滤；执行历史按真实批次数据源过滤，全局规则批次也不使用空规则归属推断访问范围，系统管理员仍可读取全部历史。

迁移前的 `quality_check_result` 若无法证明规则版本与覆盖，只显示为历史有限结果，不以当前配置补造历史。新数据级模板沿用业务数据源只读连接、固定安全查询与超时；不执行客户端 SQL。当前扫描最多使用 100,000 行样本，因此样本外重复值、空值或孤儿记录可能无法发现，结果说明以实际检查覆盖为准，不视为全表证明。

## 规则配置与结果查看

按用户进一步确认的产品模型，质量规则是唯一的规则管理工作区。检查项表示系统已有的可复用检查方法；一条规则把某检查项与适用对象、异常条件、风险和处理建议组合。比如同一个空值率检查项，可以配置订单客户编号阈值 5%、客户手机号阈值 20% 两条规则。用户新增的是可执行检查配置；当前没有新增自定义检查方法或条件表达式的能力。

侧栏移除“检查类型”，`/admin/governance/rule-templates` 仅作为旧链接跳转到 `/admin/governance/rules?showChecks=1`，原数据源/快照上下文保留，说明弹窗由规则页消费标记后打开。规则页的“支持的检查项”和新建表单复用 `QualityCheckItemCatalog`，提供名称/用途/编码搜索、维度/方式筛选及默认条件、参数、范围和证据限制；查看不产生写操作。有维护当前规则范围能力时，明确选择检查项才打开或预填规则表单，无维护权时只能浏览说明。

新建按“检查什么问题 → 适用于哪些对象 → 什么情况算异常 → 发现异常如何处理”四部分组织；固定条件直接解释，数值参数保留类型/范围校验，空值率额外说明 0–1 比例与百分数的关系。检查项说明不单独占用菜单，也不要求用户管理执行器或模板。既有模板编码、规则 ID、历史版本与权限合同继续沿用。

质量规则列表突出检查条件、适用对象、覆盖关系、风险与配置状态；顶部说明启用不等于已执行，快照不限定规则配置生命周期。表单提供不执行检查的配置预览，实际目标覆盖和覆盖优先级仍由执行计划判定，不把静态预览当作试运行结果。

行内“执行记录”直接打开真实历史。历史 VO 增加 `batchDatasourceId` 与 `snapshotId`，从已授权的实际批次读取，不能以全局规则的空归属或当前页面范围代替。记录区分执行完成且发现问题、执行完成未发现问题、失败、不适用和未执行，并保留历史配置版本和原因。具备该批次源的问题查看能力时可进入对应快照、SOURCE 维度及目标表的**同范围当前问题**；该入口不声称只展示当次批次或某条规则的问题。缺少实际范围时不提供跳转。

`TIME_TABLE_NO_UPDATE` 的检查项显示名修正为“表估算行数为零”，原编码、执行逻辑、已有规则实例名称与历史记录不变；它不能证明表为空或长期未更新。抽样型检查提示 100,000 行上限，数据新鲜度说明需要可读时间字段；未发现问题不等于全表无问题。上述变化不新增迁移或改变规则启用状态，历史范围字段的验证见[项目现状](../项目现状.md)，当前页面模型和人工验收边界见[项目现状](../项目现状.md)。

## 适用性、执行结果与业务判断

规则是否适合分两层判断：业务上需要确认字段是否必填/唯一、同名是否同义、时间字段是否代表更新等前提；技术上由选定快照的真实执行规划确认对象、优先级与条件。检查项选择器、已选检查项和规则详情都提供业务适用场景与前提，不能把 `READY` 规划状态当作业务需求已确认或质量通过。

`GET /api/admin/snapshots/{snapshotId}/quality-rules/summary` 沿用快照级 `governance:rule:view` 授权，复用 `QualityRuleExecutionPlanner` 返回当前启用配置的逐条规划，不新增检查执行端点。响应带 `datasourceId/snapshotId`、全局继承与本源专用候选数、可参与/不适用/配置异常数量，以及 `rules` 中的规则 ID、配置版本、范围、状态、原因、潜在目标数和预计覆盖数。读取只查询应用元数据与已有批次，不调用业务检查器、不读业务源、不创建批次或修改问题、评分、状态。真正执行时仍重新规划并检查当前权限和连接。

总览解释候选数与可参与数的差异，并列出不适用、异常及部分覆盖原因；没有完整批次时，零问题仅代表暂无问题记录，历史评分独立显示。规则页的规划基于该快照全部启用配置，不随下方列表筛选/分页变化；列表可包含停用配置，逐行区分继承全局/本源专用、配置启用与当前快照生效。全局默认规则库只维护跨源定义，不显示为某个快照已生效；具体源和更精确的表/字段规则按现有优先级覆盖。

规划响应按源、快照与请求版本隔离；切换范围立即清空旧规划，旧响应不覆盖新范围。配置变更刷新规划，历史执行仍使用当时配置版本，配置记录以自然语言条件展示。新建/编辑当前采用分区表单与保存前核对区，窄屏回归单列；核对区为静态配置预览，不对草稿执行扫描或伪造预计数量。

治理状态维护只使用顶部公共 ScopeBar，不再自行列出快照或在历史快照缺失时回落最新版本。高影响确认固定原快照及对象；确认期间切换快照/表会取消原写动作，旧写响应不更新新范围。表/字段加载失败独立显示，表可按名称/说明搜索，选择表支持键盘，字段展示业务说明。问题中心、治理状态维护与字段治理补充各自职责说明，复用同一治理视觉节奏；业务解释与脱敏入口仍归既定模块。

## 规则与问题的呈现

治理处理页使用清晰列表；总览按当前来源与快照呈现分析图形，来源检查与规则规划归执行依据，问题处理留在专页。图形、布局和密度可按实际任务调整，统计口径与权限边界保持明确。规则页的只读规划使用检查覆盖抽屉，逐条异常保留需核对入口，完整使用前提和风险在详情中查看。表内不重复展示已启用与可参与等不同口径，启停和执行记录从更多菜单进入，仍执行原有权限与请求隔离。规则列表条件摘要不改变检查逻辑，历史条件与原始参数继续按记录解释。

问题中心将低频筛选收起；治理状态维护以平面分栏选择表；字段标签在治理工作区内不单独列出或默认选择快照，只读取公共上下文，显式范围同步未完成时显示加载而不回退。未选表、真正无字段、错误分别处理，每个状态只显示一处引导。呈现规则见[前端规范](../development/前端开发规范.md#治理工作区的视觉取舍)。

## 权限与页面

治理总览从 Java 恢复最近可核验批次，单独显示快照记录评分、最近检查结果和待处理问题全量统计；问题预览不用于推算总数。质量规则页提供模板、维度、范围和启用/删除筛选，以及详情、修订历史、编辑、复制、启停、软删除和停用恢复。治理状态维护仍在独立入口。

前端根据 IAM-SIMPLE-1 能力控制呈现；服务端最终授权。数据源规则写入要求该源 `governance:rule:manage`，改范围时同时核验原范围和目标范围；全局规则写入要求受保护系统管理员身份。权限不从旧角色或 JWT authorities 推导。变更修订保留真实操作人；通用操作审计继续记录请求操作。实际验证范围见[项目现状](../项目现状.md)。

2026-10-20 问题中心布局：数据源/快照只从 AdminShell ScopeBar 读取；问题筛选不另行初始化范围或自动选择快照。显式 query 快照由公共上下文按 ID 验证和恢复，失败时不回落。列表总数取服务端分页 `total`；当前页 records 只用于绘制列表，不能推导全量严重程度或状态汇总。零记录、筛选无匹配、加载、读取失败、无权和未选范围分开显示。详情、责任人分派、批量部分成功、同表审核记录与返回发布流程仍按原 IAM-SIMPLE-1 能力控制。实际验证范围见[项目现状](../项目现状.md)。

2026-10-20 问题处理一致性：分派只更新 assignee 与更新时间；状态流转使用 ID 和预期状态作为条件更新，受影响行数为 0 时返回冲突。`resolvedBy/resolvedAt/resolutionNote` 表示当前一次解决/驳回；进入 REOPENED 或 CONFIRMED 显式清空，再次解决/驳回写入本轮处理人、时间和备注，null 备注会落库清空。批量仍先整批做 IAM/归属校验，再逐条流转；无效状态和并发状态冲突按真实原因跳过。

问题状态是主事务结果。CONFIRMED/RESOLVED 的置信度联动只在主事务提交后调用 `ConfidenceCalculator.adjustScoreAfterCommit` 的独立 REQUIRES_NEW 事务；联动异常记录警告并回滚该辅助事务，不回滚已提交问题状态。主事务回滚不触发置信度事件。直接跳过 Spring 事务代理的调用不执行联动并记录警告；生产服务调用依赖 Spring 代理。

历史审查中的缺陷是当时记录。未复核的项目不直接写成当前缺陷或新待办；需先定位现存代码，再进入[后续开发](../development/后续开发.md)。

### 问题与规则的恢复保护

总览把SOURCE诊断、独立SEMANTIC人工事实与当前实际批次/规划分开，实际执行0、未知、部分失败和历史分保留。刷新只重读已核验范围的证据，不隐式选择新快照。问题表/用户目录分别要求metadata:view、organization:user:view，辅助无权或挂起不阻已核验问题范围的合法接口。语义解决/驳回填写已有resolutionNote，服务端继续复核最新语义批次及当前hash；409保留原问题依据。批量保留全部skipped原因，不宣称全业务原子或全部成功。问题处理的读取与写入按身份/IAM/精确源快照代次失效，其未知写共享内存目标锁仍不替代幂等、真实CAS或跨刷新恢复；规则配置已使用上文[独立持久协议](#规则写入合同)，不将其能力外推至问题处理。问题页既有覆盖见[第七阶段验收](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d7)。

质量规则按配置→只读规划→来源检查呈现，源级规则列表不额外要求metadata或快照，规划只读精确公共快照；最近采集目录辅助预算不等于历史范围。规则保存以原版本条件和操作回执恢复，详见上文[工作区职责与恢复](#工作区职责与恢复)。治理状态调整不自动修复问题或审核知识，批量updated/excluded缺失仍保留既有未知锁，不能用本轮规则协议宣称状态维护也已具备跨刷新恢复；既有覆盖见第七阶段验收B。

问题中心恢复维度、级别或责任人筛选时自动展开「更多筛选」；用户主动收起后保留活动条件数，收起不清除筛选。质量规则的「清除筛选」位于主工具栏，有活动条件才显示，不必先展开高级条件；规则列表读取失败不显示条数，不把未知补成0。治理状态维护的表搜索无匹配时可直接清除搜索，表计数区分读取中与失败；状态选择器的可访问名称包含所属表或表＋字段，沿用原能力与危险操作确认。

`IssueList.fetchIssues` 仅对仍属于当前请求和快照的列表失败记录诊断：固定阶段 `ISSUE_LIST` 与快照ID，不输出问题描述、筛选值或原始异常对象；旧范围的迟到失败不记录为当前失败。
