# 004 元数据治理与可信度

> 文档类型：现行模块说明；核对日期：2026-10-17；代码基线：`619bca6`。本轮静态核对，运行验收见[项目现状](../项目现状.md)。

## 概览与主流程

负责质量规则、真实检查、问题发现/分派/处理/复查、表字段治理状态，以及与可信度、标签和治理反馈的连接。

选定源与快照 → 质量检查 → 问题分派与处理 → 复查/必要时 REOPENED → 审核发布 → 查询血缘、使用与反馈回流。

## 关键边界

- DISCOVERED、NORMAL、RECOMMENDED、DEPRECATED、BLOCKED 等治理状态按现有服务规则流转，不等同于审核状态。
- DEPRECATED/BLOCKED 表字段禁止进入检索、SQL 生成和临时访问批准；NORMAL/RECOMMENDED 本身也不能跳过来源快照、审核、build membership 或当前 IAM 检查。
- 质量分与字段可信度是辅助决策，不代替权限。可信度读时衰减可配置，默认半衰期 30 天。
- 批量处理返回 `updated/skipped/skippedIssues`；跳过是正常部分成功结果，前端必须显示原因，不整体抛错或报全成功。
- 两个问题列表的 assigneeId 和目录的数据源范围均下推 SQL，禁止分页后再过滤。

## 代码与接口

| 代码 | 用途 |
| --- | --- |
| [MetadataGovernanceController](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java) | `/api/admin`下 quality-check、quality-rules、quality-issues、治理状态与 review-records |
| [governance/checker](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/checker/) | 元数据维度与数据质量检查 |
| [governance/service](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/) | 问题、状态、审核与质量聚合 |
| [fieldtag](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/) | 标签、可信度、反馈 |
| [QualityDashboard](../../frontend/src/views/admin/governance/QualityDashboard.vue)、[IssueList](../../frontend/src/views/admin/governance/IssueList.vue) | 正式质量与问题页面 |
| [governance API](../../frontend/src/api/admin/governance.ts) | 请求 DTO、筛选与批量返回 |

## 修改与验证

质量检查必须针对实际选定源和快照，记录 SQL 异常和结果；不以脚本静态通过宣称真实质量闭环。检查部分成功、无权、无快照、失败与复查。发布前和读取时仍检查治理准入，历史正常状态不能覆盖当前禁止状态。

历史审查中的缺陷是当时记录。未复核的项目不直接写成当前缺陷或新待办；需先定位现存代码，再进入[后续开发](../development/后续开发.md)。
