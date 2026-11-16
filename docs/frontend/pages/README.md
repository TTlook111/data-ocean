# 前端页面专题

按模块读取页面目的、使用者、动作和状态边界。它们是设计目标，实际完成范围统一见[原型状态](../原型状态.md)。

| 页面专题 | 业务来源 |
| --- | --- |
| [人员角色与权限](people-access.md) | [业务解说](../../product/modules/02-people-access.md) |
| [数据源接入与元数据采集](datasources.md) | [业务解说](../../product/modules/03-datasources.md) |
| [数据治理与业务知识审核](governance.md) | [业务解说](../../product/modules/04-governance.md) |
| [知识库与发布管理](knowledge-release.md) | [业务解说](../../product/modules/05-knowledge-release.md) |
| [智能问数与查询流程](query.md) | [业务解说](../../product/modules/06-query.md) |
| [用户反馈与持续改进](feedback.md) | [业务解说](../../product/modules/07-feedback.md) |
| [治理与问数运营看板](dashboard.md) | [业务解说](../../product/modules/08-dashboard.md) |
| [模型管理与安全调用](models.md) | [业务解说](../../product/modules/09-models.md) |

## 原型定位与衔接

[后台入口](../../../prototypes/后台管理/index.html)、[登录](../../../prototypes/app/login.html)及[问数工作台](../../../prototypes/app/index.html)共用同一套原型。下表定位当前原型，正式业务代码另按重构任务评估。

| 需求模块 | 原型界面与状态入口 | 必须核对的关联 |
| --- | --- | --- |
| 人员、部门与角色 | [人员](../../../prototypes/后台管理/people.js)、[角色](../../../prototypes/后台管理/roles.js)、[共用人员与当前规则](../../../prototypes/后台管理/people-data.js) | 功能角色与问数数据访问分开，部门来源按直属归属 |
| 申请、审批与授权 | [审批](../../../prototypes/后台管理/access-requests.js)、[审批状态](../../../prototypes/后台管理/access-request-data.js)、[授权来源](../../../prototypes/后台管理/grant-data.js) | 当前审批负责人、联合生效、剩余来源、审计及返回原问 |
| 数据源、采集与资料 | [页面](../../../prototypes/后台管理/datasources.js)、[状态](../../../prototypes/后台管理/datasource-data.js)、[变化影响](../../../prototypes/后台管理/governance-impact-data.js) | 成功快照、事实核实、治理依赖；停用恢复与退役留档 |
| 治理 | [页面](../../../prototypes/后台管理/governance.js)、[逐项定义](../../../prototypes/后台管理/governance-data.js) | 人工确认、依赖修订、来源影响、知识更新及禁止状态 |
| 知识与发布 | [页面](../../../prototypes/后台管理/knowledge-update.js)、[状态](../../../prototypes/后台管理/knowledge-workspace-data.js) | 已确认治理、文档与题单修订、候选测试、发布及恢复 |
| 问数 | [页面](../../../prototypes/app/workbench.js)、[运行状态](../../../prototypes/app/query-flow.js)、[跨页交接](../../../prototypes/shared/query-knowledge-bridge.js)；流程维护读[结构源](../../../prototypes/flows/问数流程.json)与[节点说明](../../../prototypes/flows/问数节点详情.json)，查看[生成图](../../../prototypes/flows/问数流程.html) | 当前授权与规则、同版知识、历史结果、申请及反馈 |
| 反馈 | [页面](../../../prototypes/后台管理/feedback.js)、[事项](../../../prototypes/后台管理/feedback-data.js) | 分派不扩权、修复依据、复验、合并、结案回告及重开 |
| 看板 | [总览](../../../prototypes/后台管理/dashboard.js)、[模块概览](../../../prototypes/后台管理/module-dashboards.js) | 统计口径、期间、当前源与业务入口；固定统计样例边界 |
| 模型与诊断 | [页面](../../../prototypes/后台管理/system-models.js)、[状态](../../../prototypes/后台管理/system-model-data.js) | 节点用途、所选与生效索引、调用快照及来源任务 |
