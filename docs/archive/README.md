# 历史材料索引

> 文档类型：历史资料入口；整理日期：2026-10-17。保留仍有参考价值的设计、审查依据和验收时间线，不表示重新认可旧方案。

先读[文档导航的冲突规则](../README.md#方案冲突怎么判断)。正文中的“唯一执行文档”“立即实施”、旧阶段状态及临时授权均只属于原记录时点。用户明确纠正的部分不得重新实施。

## 按主题查找

| 历史材料 | 如何使用 | 当前入口 |
| --- | --- | --- |
| [统一执行路线图](plans/DataOcean统一执行路线图.md)、[项目优化指导](plans/DataOcean项目优化指导文档.md) | 历史路线已完成；旧 RBAC/Policy 与旧 Agent 不再适用 | [IAM](../modules/007-iam-s1.md)、[问数](../modules/008-query-memory.md) |
| [完整权限设计](plans/DataOcean-完整权限体系设计.md)、[鉴权接入设计](plans/DataOcean-IAM-SIMPLE-1鉴权接入设计.md) | 简明 S1 模型与若干约束仍有效；“B1 未开始”等进度不是现状 | [IAM](../modules/007-iam-s1.md) |
| [B0 冻结](plans/轨道B-B0权限清单与决策冻结.md)、[B6 记录](plans/轨道B-B6删除清单与执行顺序.md) | 查冻结对象、保留对象与历史删除；不当作其他环境删除授权 | [IAM](../modules/007-iam-s1.md)、[待办](../development/后续开发.md) |
| [B5 切换与回退](plans/DataOcean-IAM-S1-B5切换与回退手册.md) | 原版本/机器/准备轮适用；跨环境重新核对和授权 | [开发指南](../development/本地开发与验证.md) |
| [LangGraph 与会话方案](plans/DataOcean-LangGraph问数与会话记忆恢复方案.md) | 保留已确认设计依据与执行历史；不重开阶段 | [问数](../modules/008-query-memory.md)、[知识](../modules/006-knowledge.md)、[RAG](../modules/009-schema-rag.md) |
| [RAG 修复方案](plans/DataOcean-RAG问题修复与知识文档切分优化方案.md) | token 切分等依据保留；旧发布即索引过程已被 build 生命周期替代 | [RAG](../modules/009-schema-rag.md) |
| [问数方案二](plans/DataOcean-问数页方案二实施指导.md)、[后台体验建议](plans/DataOcean-后台产品体验优化建议.md) | 保留已选方向；视觉与交互使用后续用户修正 | [前端规范](../development/前端开发规范.md) |
| [玻璃实施计划](plans/DataOcean-海蓝毛玻璃美化实施计划.md) | 保留设计依据；缺失源图如实标记，不重新执行历史批次 | [前端规范](../development/前端开发规范.md)、[前端验收](../acceptance/前端设计验收记录.md) |

## 其他历史资料

- [深度优化参考](plans/DataOcean深度优化参考方案.md)、[代码优化](plans/代码优化方案.md)、[优化审查](plans/代码优化方案审查报告.md)、[修复清单](plans/代码审查修复清单.md)。
- [旧 SQL generator 改造](plans/sql-generator-agent化改造方案.md)、[血缘研究](plans/data-lineage-research.md)。
- [早期项目构想](nl2sql-单库多表版-项目构想.md)、[面试材料](interview/DataOcean-飞致云面试讲稿.md)。
- [2026-06-21 审查与复核](reviews/2026-06-21-代码审查报告.md)、[2026-08-21 审查](reviews/2026-08-21-Phase1-代码审查报告.md)。

审查问题先核对现存代码，不把旧报告的问题数量或未勾选任务直接搬到当前待办。原始 SQL 夹具仍保留在 `docs/review/fixtures/`，本轮不移动或执行。
