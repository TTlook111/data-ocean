# 历史材料索引

> 状态：历史回溯入口；整理日期：2026-10-25。本目录退出默认现行资料范围，正文标注为已实施目标、历史参考或废弃执行方案。不能据此恢复旧实现、进度或临时操作授权。

先读[文档导航的冲突规则](../README.md#方案冲突怎么判断)。正文中的“唯一执行文档”“立即实施”、旧阶段状态及临时授权均只属于原记录时点。用户明确纠正的部分不得重新实施。

## 本轮退出当前导航的材料

| 材料 | 状态与现行替代 |
| --- | --- |
| [字段语义治理与问数反馈闭环目标](plans/completed/字段语义治理与问数反馈闭环改造目标.md) | 主体实施目标归档，原确认要求保留追溯；遗留消费缺口和真实验收由[项目现状](../项目现状.md)、[后续开发](../development/后续开发.md)及[问题分析](../development/RAG与问数治理适配问题.md)承接，不代表全部验收通过 |
| [旧项目状态流水](status/2026-10-24-项目状态历史.md)、[旧开发队列](status/2026-10-24-开发队列历史.md) | 历史快照；当前只读[项目现状](../项目现状.md)和[后续开发](../development/后续开发.md) |
| [字段语义旧实施方案](plans/completed/质量规则与平台内字段语义治理方案.md) | 已实施历史设计；当前以[治理模块](../modules/004-metadata-governance.md)为准 |
| [目录旧实施方案](plans/completed/数据对象目录与详情完善方案.md) | 已实施历史设计；当前以[资产模块](../modules/003-metadata-collection.md)为准 |
| [术语旧实施方案](plans/completed/业务术语与语义知识改造方案.md) | 已实施历史设计；当前以[知识模块](../modules/006-knowledge.md)为准 |
| [原治理红队审查](reviews/2026-10-24-治理闭环红队审查与最终方案.md) | 历史审查及当时修正思路；有效要求已承接到[产品目标](plans/completed/字段语义治理与问数反馈闭环改造目标.md#91-工程验收底线)，实际修复见[带日期证据](../acceptance/治理闭环TDD实施记录.md#2026-10-24-红队补强) |

## 按主题查找

| 历史材料 | 如何使用 | 当前入口 |
| --- | --- | --- |
| [完整权限设计](plans/DataOcean-完整权限体系设计.md)、[鉴权接入设计](plans/DataOcean-IAM-SIMPLE-1鉴权接入设计.md) | 废弃执行方案，仅查引入背景；当前权限合同不从旧阶段推断 | [IAM](../modules/007-iam-s1.md) |
| [B0 冻结](plans/轨道B-B0权限清单与决策冻结.md)、[B6 记录](plans/轨道B-B6删除清单与执行顺序.md) | 查冻结对象、保留对象与历史删除；不当作其他环境删除授权 | [IAM](../modules/007-iam-s1.md)、[待办](../development/后续开发.md) |
| [B5 切换与回退](plans/DataOcean-IAM-S1-B5切换与回退手册.md) | 原版本/机器/准备轮适用；跨环境重新核对和授权 | [开发指南](../development/本地开发与验证.md) |
| [LangGraph 与会话方案](plans/DataOcean-LangGraph问数与会话记忆恢复方案.md) | 保留已确认设计依据与执行历史；不重开阶段 | [问数](../modules/008-query-memory.md)、[知识](../modules/006-knowledge.md)、[RAG](../modules/009-schema-rag.md) |
| [RAG 修复方案](plans/DataOcean-RAG问题修复与知识文档切分优化方案.md) | token 切分等依据保留；旧发布即索引过程已被 build 生命周期替代 | [RAG](../modules/009-schema-rag.md) |
| [问数方案二](plans/DataOcean-问数页方案二实施指导.md)、[后台体验建议](plans/DataOcean-后台产品体验优化建议.md) | 保留已选方向；视觉与交互使用后续用户修正 | [前端规范](../development/前端开发规范.md) |
| [玻璃实施计划](plans/DataOcean-海蓝毛玻璃美化实施计划.md) | 保留设计依据；缺失源图如实标记，不重新执行历史批次 | [前端规范](../development/前端开发规范.md)、[前端验收](../acceptance/前端设计验收记录.md) |

## 其他历史资料

- [血缘研究](plans/data-lineage-research.md)。
- [早期项目构想](nl2sql-单库多表版-项目构想.md)、[面试材料](interview/DataOcean-飞致云面试讲稿.md)。
- [2026-06-21 审查与复核](reviews/2026-06-21-代码审查报告.md)、[2026-08-21 审查](reviews/2026-08-21-Phase1-代码审查报告.md)。

审查问题先核对现存代码，不把旧报告的问题数量或未勾选任务直接搬到当前待办。原始 SQL 夹具仍保留在 `docs/review/fixtures/`，本轮不移动或执行。
