# 平台配置、通知与工作台

> 状态：现行模块与代码导航；核对日期：2026-11-01。行为边界与定位在此维护，运行结论见[项目现状](../项目现状.md)及[验收索引](../acceptance/README.md)。
>
> 返回[功能导航](README.md)。本页不授权执行配置写入、供应商测试或数据变更。

## AI 配置

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 模型配置、供应商与密钥展示 | [AiConfigServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/service/impl/AiConfigServiceImpl.java)：`getConfig`、`updateConfig`、`upsertProvider`、`maskProvider` | 活动聊天配置、待切换 Embedding 配置和脱敏返回。 |
| 连接测试、模型同步与维度检测 | [AiConfigController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/controller/AiConfigController.java)：`testProvider`、`syncModels`、`detectDimension` | 经 Python 客户端访问供应商；这些是运行操作，文档阅读不授权调用。 |
| 配置持久化 | [SysConfigServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/service/impl/SysConfigServiceImpl.java)：`getValue`、`setValue`、`getByPrefix` | sys_config 是配置事实源。 |

AiConfig.vue → AiConfigController → AiConfigServiceImpl → sys_config；保存后通过 PythonAiConfigClient 通知重载。Embedding 改变时先记录 pending 和 REINDEX_REQUIRED，不能将配置保存等同于已有 build 已重建。

AI 配置缺失不应阻止服务启动；内部令牌与 JWT 密钥则属于独立启动安全边界。Prompt 编辑/审核/生效读取见[Prompt 定位](../技术栈与模块职责.md#prompt-修改定位)。

## 通知与操作日志

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 通知发送、列表、已读与未读数 | [NotificationServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/service/impl/NotificationServiceImpl.java)：`send`、`listMyNotifications`、`markAsRead`、`markBatchAsRead`、`countUnread` | 收件人归属与已读状态。 |
| 操作日志写入与列表 | [OperationLogServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/service/impl/OperationLogServiceImpl.java)：`record`、`listLogs` | 后台操作日志；不等同于查询审计。 |

通知与操作日志分别经对应 Controller/Service 读写；操作日志的横切入口在 system/aspect。改查询执行统计应转到[审计与血缘](011-audit-lineage.md)，不要合并两类日志口径。

## 工作台与告警配置

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 工作台入口与统计 | [DashboardServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/dashboard/service/impl/DashboardServiceImpl.java)：`getStats` | 各卡片按所属功能与负责源裁剪。 |
| 来源预览、选中对象与审批预览 | [AdminHomeView.vue](../../frontend/src/views/AdminHomeView.vue)：`loadReadiness`、`loadApprovals`、`preview`、`selected`、`persistFocus` | 最多三个来源与两条待审预览，显式定位、当前对象高亮和下一步一致。 |
| 准备条件与下一步映射 | [workbenchPresentation.ts](../../frontend/src/utils/workbenchPresentation.ts)：`validWorkbenchReadiness`、`workbenchSourceAction`、`workbenchCondition` | 严格核验回执；只对已知阻断码生成既有工作区链接，不执行后端 actionPath。 |
| 告警规则维护 | [AlertController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/AlertController.java)：`listRules`、`createRule`、`updateRule`、`toggleRule` | 这里只定位规则 CRUD；不证明告警执行、历史与通知链已完整接通。 |

DashboardController.getStats → DashboardServiceImpl.getStats；工作台采用获准 V3 的来源准备预览（最多三个）与选中来源下一步；无来源查看权时按字段/知识/审批能力提供入口，审批 total 与最多两条预览分开。只读现有 API，不在首页执行发布、审核或授权；后台准备判定不等于个人查询授权。展示规则见[前端规范](../development/前端开发规范.md#工作台与治理总览-v3已批准)。列表选中态、详情与下一步共用经过URL及有权目录核验的当前对象，不能将本地默认项单独作为高亮依据；无效/无权定位不高亮其他来源。不能由工作台入口权限推导所有卡片数据权限，读取失败也不能显示成 0。

| 工作台内容 | 权威读取与范围 | 不得推导的事实 |
| --- | --- | --- |
| 来源名称与准备回执 | `datasources/simple`按datasource:view负责源裁剪；readiness/batch每轮至多100来源，显示最多3个预览 | 未读/缺失不是就绪；未知存在时总体分类数未知。后台permissionReady是存在有效授权，不代表本人有查询权，askable不新增knowledgeReady门禁。 |
| 来源版本与下一步 | 同一DTO的publishedSnapshotId/version及latestCollectedSnapshotId/version，目标动作按已知业务码及同源能力映射 | 最新采集不等于已发布；不信历史actionPath任意导航，不额外读取无权元数据。局部focusSourceId不扩散为其他页筛选。 |
| 未解决问题摘要 | `dashboard/stats.openIssues`按issue:view负责源汇总 | 不是个人分派、选中来源或当前快照的计数；问题中心只是普通入口，不承诺默认精确列出同一集合。 |
| 审批预览 | `listIamS1AccessRequestQueue('PENDING',1,2)`的total及最多2条，security:approval:view | 查看不等于批准或分派；不额外要求datasource/metadata辅助权；没有个人活动接口就不伪造“继续上次”。 |

告警执行与历史的完成程度沿[后续开发](../development/后续开发.md)核对，不因规则接口存在宣布告警已完成。

## 测试与验证入口

- [OperationLogServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/impl/OperationLogServiceImplTest.java)：操作日志。
- [OperationLogAspectTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/aspect/OperationLogAspectTest.java)：日志切面。
- [NotificationRecipientResolverTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/NotificationRecipientResolverTest.java)：通知收件人解析。
- [Batch6CapabilityControl.test.ts](../../frontend/src/views/admin/operations/Batch6CapabilityControl.test.ts)：运营页面能力控制。

- [WorkbenchV3.test.ts](../../frontend/src/views/WorkbenchV3.test.ts)、[WorkbenchLifecycle.test.ts](../../frontend/src/views/WorkbenchLifecycle.test.ts)、[AdminHomeView.test.ts](../../frontend/src/views/AdminHomeView.test.ts)：来源预览、准备条件、审批与定位恢复。

本轮没有定位到专门的 AiConfigServiceImpl 或 DashboardServiceImpl 单元测试文件，不能用上述日志/页面测试替代配置与统计验证。相关修改应补充定向测试；通知测试仅覆盖收件人解析，不覆盖全部已读操作。模型连通、配置重载、页面交互均需独立验收。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)。


### 操作审计前端 V2（2026-10-29）

操作日志保留全局operation-log:view，不借查询audit权限。列表唯一后端读取，详情取本次列表行，身份/IAM/筛选/刷新使旧详情及原参数失效；历史参数按需转义文本展开，不承诺完整/统一脱敏。成功仅表示调用未抛异常，HTTP推断类型与路径首数字目标都不是业务结果证明。总数服务端、其他统计当前页，缺状态/耗时不补失败/零。URL页大小/筛选/时间恢复一致，无效/缺一端/倒置时间不退全量，需明确改/清。没有新增导出、独立详情或聚合API。见[血缘与审计V2验收](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d6)。

## 平台工作面

工作台按V3展示有权来源准备与下一步，审批只读两条有限预览；不是个人待办/最近任务。未知不造0。运行状态三Tab保留服务UNKNOWN/失败、精确池重置与规则配置；不新增告警评估/历史闭环。AI配置保持Chat/Embedding/active/pending分开，密钥不回显；provider检测归属未核时暂禁自动维度检测，Chat写清pending的后端副作用不借前端绕过。Prompt维护/审核独立，精确版本与场景草稿保护，回滚创建新草稿不立即激活。通知当前可见批读后重读全局计数，身份/读写归属受前端保护；上述不改变Java授权与配置语义。见[阶段D9证据](../acceptance/2026-10-29-统一跨页终验V2.md#v2-d9)。

`RuntimeView.vue`的服务、SQL池和规则Tab保留URL恢复，池重置仅sys＋manage且确认精确对象/原因。`AiConfig.vue`保留供应商/模型、测试/同步、参数、激活和待切换状态，未核对provider归属的维度检测不开放。`PromptsView.vue`保留场景、草稿、校验、版本、提交/审核/批准激活/驳回、回滚、日志与效果，旧语义路由仅作带query和hash的兼容跳转。两类指南只描述当前功能，404返回有权工作区或资料，不据旧规格新增告警闭环或配置入口。认证和本人表单见[账号模块](001-user.md#账号页面-v2)。
