# 004 元数据治理与可信度

旧版对数据源快照执行质量检查，并维护字段解释、业务知识和问数反馈。来源质量检查与语义复查分属 `SOURCE`、`SEMANTIC` 两类证据。

## 质量规则与问题

- 规则实例绑定已注册模板，配置可作用于全局、数据源、表或字段。执行时对同一模板与目标按范围选择有效配置。创建、编辑、复制、启停、删除和恢复会登记操作回执；配置保留修订历史，更新使用预期版本校验。[规则配置](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityRuleServiceImpl.java) · [操作回执](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityRuleOperationServiceImpl.java)
- 来源检查按目标快照运行规则，批次保留实际采用的规则、执行结果和覆盖情况。问题支持确认、解决、驳回、重开、分派和批量处理。处理前校验数据源、操作权限和问题行版本，批量结果分别列出成功及跳过项。治理总览按快照统计未解决余量与每日进出。[检查服务](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityCheckServiceImpl.java) · [问题处理](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImpl.java) · [问题趋势](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueTrendServiceImpl.java)
- 规则修订、执行批次、逐规则结果及问题分别持久化。接口、检查与处理页面按同一数据源和快照读取这些记录。[治理接口](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java) · [持久化对象](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/entity/po) · [质量检查页](../../frontend/src/views/admin/governance/QualityChecksView.vue) · [问题中心](../../frontend/src/views/admin/governance/IssueList.vue)

## 字段解释与业务知识

- 字段解释保留原注释、来源绑定及各次语义修订。人工可编辑草稿、采纳 AI 候选、提交审核或撤回，并对精确来源与修订复查；平台上的解释不会改写业务库注释。[字段解释](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServiceImpl.java) · [语义检查](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/SemanticCheckExecutionService.java) · [字段解释页](../../frontend/src/views/admin/governance/FieldSemanticWorkspace.vue)
- 治理任务按快照发现知识缺口，排队后由后台处理，支持重试。数据源用途、表粒度、业务关系、指标的修订各自绑定来源与依赖，经过审核后供[知识模块](006-knowledge.md)使用。[任务服务](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceTaskServiceImpl.java) · [后台调度](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceTaskWorker.java) · [业务知识](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/BusinessKnowledgeServiceImpl.java)

## 标签、使用证据与反馈

字段标签和来源可信度保留独立记录。问数使用档案按实际使用的字段及语义版本登记证据，供管理端核对反馈归因。需再治理的反馈在修订后按原提问情景和预期结果进行人工核验。[字段标签](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/service/impl/FieldTagServiceImpl.java) · [来源可信度](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/service/impl/FieldConfidenceServiceImpl.java) · [版本使用档案](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceFieldProfileServiceImpl.java) · [反馈处理](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/GovernanceFeedbackServiceImpl.java)
