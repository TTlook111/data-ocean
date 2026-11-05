# 业务术语与 AI 释义

> 状态：现行代码导航；核对日期：2026-11-05。这里只核对源码定位，不代表本轮运行或业务验收通过。
>
> 上一级：[模块说明](../006-knowledge.md) · [功能导航](../README.md)

## 功能说明

定位术语修订审核、AI 候选生成与人工采纳。独立字段解释的编辑审核由治理模块维护。

## 修改位置速查

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 术语编辑、关联和工作流 | [GlossaryTermWorkflowServiceImpl.java](../../../backend/DataOcean/src/main/java/com/dataocean/module/glossary/service/impl/GlossaryTermWorkflowServiceImpl.java)：`save`、`link`、`action`、`approvedFact`、`factValid` | 定义、完整映射、精确修订和审核记录。 |
| 术语目录、筛选与空态 | [GlossariesView.vue](../../../frontend/src/views/admin/semantics/GlossariesView.vue)：`visibleTerms`、`clearTermFilters`、`loadTerms` | 筛选无匹配可直接清除；真实空集合的创建动作仍需维护能力。 |
| AI 候选与确认 | [KnowledgeSemanticServiceImpl.java](../../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeSemanticServiceImpl.java)：`generate`、`confirm`、`validate`、`membershipValid` | 生成不直接覆盖正文；人工确认后仍需审核发布。 |
| 字段解释进入知识 | [FieldSemanticServiceImpl.java](../../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServiceImpl.java)：`knowledgeFacts`、`validateKnowledgeFact`、`membershipValid` | 精确修订与当前来源资格。 |

## 实现链路

已审核术语作为生成输入；KnowledgeSemanticServiceImpl.generate 产生候选，confirm 采纳为文档修订。发布及新查询 membership 继续验证来源。

## 关键约束与关联影响

定义改变或重新审核可能使旧引用失效。修改字段草稿、审核与复查请回到[治理定位](../004-metadata-governance.md#修改定位)，不要在知识模块另建字段工作流。

## 测试与验证入口

- [GlossaryTermWorkflowServiceImplTest.java](../../../backend/DataOcean/src/test/java/com/dataocean/module/glossary/service/impl/GlossaryTermWorkflowServiceImplTest.java)：术语修订工作流。
- [KnowledgeSemanticServiceImplTest.java](../../../backend/DataOcean/src/test/java/com/dataocean/module/knowledge/service/impl/KnowledgeSemanticServiceImplTest.java)：候选生成与采纳。
- [FieldSemanticServicePersistenceTest.java](../../../backend/DataOcean/src/test/java/com/dataocean/module/fieldsemantic/service/impl/FieldSemanticServicePersistenceTest.java)：字段语义持久化。

核对重复采纳、过期候选、来源变更、完整依赖和人工内容保护；真实模型生成质量另行验收。

第七阶段术语前端：URL恢复只消费精确group/term，用户选择才写入；定义和映射取同fresh revision，workspace读取失败禁写，写错保留草稿可显式修正重试。术语目录独立glossary能力，不补metadata；原BROKEN/inactive源权限及未绑定新草稿合同不变。审核冲突保留依据；分组删除清楚说明全部术语/关联。验收见第七阶段F。

术语列表有记录但筛选无匹配时，空态动作清除关键词和状态条件，不引导创建重复术语；真实空分组或空目录仅在有维护能力时提供新增动作。搜索与状态控件有独立可访问名称，分组编辑/删除名称包含目标分组，当前分组和术语通过 `aria-current` 表达选中。以上呈现不扩大服务端授权。

`loadTerms` 仅在账号、请求和分组仍匹配时记录列表读取失败，诊断内容为固定阶段 `TERM_LIST` 与分组ID；不输出业务定义、搜索词或原始异常对象，旧请求的迟到失败不回填或记录为当前失败。
