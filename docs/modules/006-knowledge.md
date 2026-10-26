# 006 业务术语与知识文档

> 状态：现行模块说明；整理日期：2026-10-25。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 按修改意图继续阅读

- [文档生成、审核与发布](knowledge/文档生成审核与发布.md)：草稿、版本、审核与发布定位。
- [业务术语与 AI 释义](knowledge/业务术语与AI释义.md)：术语修订、候选生成、人工采纳与事实资格。
- [RAG 构建与集合生命周期](rag/构建与集合生命周期.md)：发布后的独立构建动作。

专题展开文件、方法与测试入口；本页保留行为合同，代码核对不替代运行验收。

## 概览与关键决定

2026-10-24 已按[治理闭环目标](../archive/plans/completed/字段语义治理与问数反馈闭环改造目标.md)接入已审核复查的数据源用途、表粒度、关系与指标事实，按主题生成多篇文档。`KnowledgeDocumentPartitioner` 组织文档组，单篇校验其事实，生成时比对预期业务事实。红队补强后，构建确认与执行的整组校验同时精确比对当前有效业务事实类型/修订集合，拒绝遗漏、额外引用与重复；现阶段不支持未经声明的部分业务覆盖。实时置信度通过独立字段档案读取，不进入生成输入或正文。实现与实际验收边界见[治理闭环 TDD 记录](../acceptance/治理闭环TDD实施记录.md)。文档审核发布与明确确认 RAG 构建仍是独立环节。

Java 管理术语定义与字段映射、修订审核、知识版本、发布、事实校验和构建确认；Python 根据固定输入整理目录、生成 AI 释义候选并负责切分/向量操作。**生成结构目录与 AI 补写是两个动作；发布知识文档和明确确认 RAG build 也是两个独立动作。**

独立字段解释修订通过 `FIELD_SEMANTIC` 标记引用精确 definition/revision/review/binding 与完整依赖 hash，发布验证、chunker 和 query-time membership 共用该合同。现有 SEMANTIC_NOTE 候选流程保留；字段解释工作面位于语义中心，文档和 build 生命周期分别处理。真实验证边界见[项目现状](../项目现状.md)。

```text
DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED
审核拒绝 → 返回草稿修订
PUBLISHED 的合规版本 → 有权人员明确确认数据源级 RAG build
```

旧文档中的“发布 → INDEXING → 自动索引成功后 PUBLISHED”不是当前发布主链。枚举仍有 INDEXING 不构成恢复旧流程的理由。

## 内容与审核

文档生成统一使用 Java 筛选后的固定快照，不接收动态分数，也不将完整结构发送模型。AI 只走独立候选入口；非空文档重新生成独立文档组，保留人工正文。

业务事实使用 `BUSINESS_DATASOURCE/TABLE/RELATION/METRIC`，携带精确修订、内容/来源 hash 和完整表字段依赖。字段语义变更也会使依赖它的关系/指标失效。原始外键和推断关系只生成 `JOIN_CANDIDATE` 线索，不能当作已审核业务连接。来源 JSON 按键排序规范化后校验，数据库 JSON 格式变化不改变资格；历史非规范化证据不静默重写，失配需重新确认。

六类内容为文档来源、核心表说明、具体 Join 条件、带 SQL 表达式的指标、字段说明和常见查询场景。已确认的完整性要求是：文档体系覆盖所绑定快照中的表和字段，可按业务域或表拆成多篇，也可用完整字段清单承接；不要求单篇巨型文档。该要求不能被“只描述常见字段”的旧模板覆盖，具体覆盖程度需按版本清单核对。模型根据字段名猜测的口径、未审核 Join 或指标必须标记待确认，不进入已发布事实。

发布校验当前版本的 APPROVED 审核状态、来源快照与事实依赖，以及表字段治理状态。Java 保存版本、审核任务与 chunk/build membership，不在 Java 重新实现 Python token 切分。

## 业务术语维护与生成输入

“术语分组”组织业务概念，不要求对应一张数据库表，也不绑定单一数据源。术语统一填写业务名称、定义/口径、同义词，并逐级选择数据源、已发布快照中的表与字段。草稿可以暂不关联；提交审核必须有定义和有效映射。定义、映射与状态共用修订号和事务，待审核/已通过均禁止直接编辑；退回草稿后重新审核。旧更新和关联接口经过相同门禁。

术语按业务概念建立，一张数据库表可以对应多个术语；同一个概念也可以映射多个同义字段。定义中的业务口径帮助解释数据，不自动成为数据库约束、权限规则或可执行指标公式。

V65 新增不可变审核快照、意见和 AI 候选持久化。历史术语不补造审核证据：没有覆盖完整映射的当前审核记录时，页面提示重新确认，知识生成明确列为排除项。字段候选服务端过滤、分页、搜索，真实归属、治理状态和 S1 同绑定管理范围由 Java 校验。

生成分为两个动作：

| 动作 | 依赖及结果 |
| --- | --- |
| 生成快照目录 | 程序整理所选已发布快照的完整表字段结构、关系/血缘与适用的已审核术语；没有术语、模型不可用仍可生成；不读取业务行 |
| AI 辅助补写业务释义 | 在已保存的快照草稿上，按合规表范围发送元数据与完整审核术语依赖，Python 受控分批生成独立候选；人工选择、修改并明确确认后追加到新的待审核草稿版本 |

实际输入的用途如下：

| 输入 | 用途和边界 |
| --- | --- |
| 数据源与已发布快照中的表、字段、类型、主键、治理状态 | 生成完整结构目录，固定真实对象归属；禁用/废弃对象只进入管理覆盖核对 |
| 原始表字段注释 | 保留来源并标为待业务确认，不直接充当审核通过的业务口径 |
| 表关系与同快照血缘 | 按来源及审核状态整理；候选关系不作为已确认 Join，血缘不自动转成 Join |
| 适用当前源和快照的已审核术语 | 纳入定义、同义词和完整字段映射，冻结修订、审核记录及摘要；来源不完整时明确排除 |
| AI 补写范围及上述合规元数据/术语 | 生成独立待确认候选，不自动读取业务行，不凭空补成已审核公式或枚举含义 |

生成前展示快照版本、覆盖范围、拟纳入的术语及排除原因。术语冻结 term/review/revision/hash 和完整字段依赖；跨源或不完整依赖不能裁剪成有效本源事实。原始注释仍是待确认输入。补写提示词限制为释义，不要求 SQL、Join 或无依据的公式；字段依赖由服务端校验，不接受模型自报审核通过。

候选生成不修改正文；采纳使用文档版本锁，拒绝重复、跨文档、过期候选和已变化术语，保留原目录/人工内容。AI 欠费、超时或无效输出提示失败与重试，未确认结果不会成为已发布事实。确认采纳后仍需文档审核、发布和单独构建。

`GLOSSARY_TERM` 与 `SEMANTIC_NOTE` 由 Java 校验真实来源、精确审核记录、正文和完整依赖；`TABLE_EXCLUDED/COLUMN_EXCLUDED` 只承担禁用/废弃对象的管理覆盖核对，不进入语义检索。`GET /{id}/source-status` 只读核对当前保存版本，来源变化时显示需重新确认，不重写已发布正文。重新审核同一文字也会使引用旧审核记录的知识失效。

`FIELD_SEMANTIC` 是独立于文档候选的受控事实类型。生成目录时 Java 只输出当前精确来源绑定、审核证据和依赖仍有效的修订；发布前校验 marker 与正文，活动 build membership 在每次问数候选读取时再复核当前授权。字段修订批准、撤销、来源变更会使引用旧版本的事实从新查询候选即时排除，旧向量集合仍按 build 引用生命周期管理。字段工作面通过当前 build membership 显示“已收录/待更新/待构建/不可用”，不会自动确认构建。

实现见 [术语工作流](../../backend/DataOcean/src/main/java/com/dataocean/module/glossary/service/impl/GlossaryTermWorkflowServiceImpl.java)、[术语来源](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/GlossaryKnowledgeSourceServiceImpl.java)、[AI 释义](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeSemanticServiceImpl.java)。本轮范围见[验收记录](../acceptance/业务术语与语义知识验收记录.md)。

## 关键入口

| 代码 | 用途 |
| --- | --- |
| [KnowledgeDocController](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java) | `/api/admin/knowledge-docs`下 CRUD、review、publish、版本、预览与 rag-builds |
| [KnowledgeDocCrudService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocCrudService.java) | 列表、创建与编辑 |
| [KnowledgeDocLifecycleService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocLifecycleService.java) | 审核与文档发布 |
| [KnowledgeDocPublishService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocPublishService.java) | 快照目录生成与预览；类名不表示负责活动 build 切换 |
| [KnowledgeSnapshotFactValidator](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/support/KnowledgeSnapshotFactValidator.java) | 快照事实校验 |
| [KnowledgeView](../../frontend/src/views/admin/semantics/KnowledgeView.vue)、[KnowledgeDocView](../../frontend/src/views/admin/semantics/KnowledgeDocView.vue) | 语义知识工作区与详情 |

审核理由、来源快照、版本、切分预览与历史向量任务的读取入口以 KnowledgeDocController 为准，实现按上表职责分拆。

## 构建与验证入口

`POST /api/admin/knowledge-docs/rag-builds`确认数据源/来源快照下的构建，`GET`读取构建列表。Java 冻结合规版本清单，后续执行、校验和清理由[RAG 模块](009-schema-rag.md)说明。文档状态不等于索引状态。

2026-10-25 复核确认：知识页部分范围读取存在旧响应覆盖新数据源的问题，尚未修复；文档/切片生成成功也不能证明纯表级事实与新业务关系已被问数完整消费。复现边界见[语义中心复核记录](../acceptance/2026-10-25-语义中心复核记录.md)，消费断点见[RAG 与问数治理适配问题](../development/RAG与问数治理适配问题.md)。

改审核与发布时验证无审核、错误快照、禁止字段、重复操作和并发冲突；改构建时同时核对 build 失败不替换活动指针、旧查询引用不被清理。原始历史方案仅供追溯。
