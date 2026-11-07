# 平台配置、通知与工作台

> 现行行为与代码导航。运行结论见[项目现状](../项目现状.md)及[验收索引](../acceptance/README.md)。
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

工作台 `/admin/workbench` 通过 `GET /api/admin/dashboard/query-experience` 展示当前负责范围内的问数体验。慢回答和点踩反馈在独立只读明细页分页。旧来源书签 `focusSourceId` 仍需在当前身份下校验来源及 `datasource:view`，合法书签进入对应的数据源详情。[工作台界面](../../frontend/src/views/AdminHomeView.vue) · [明细页](../../frontend/src/views/admin/workbench/ExperienceDetailsView.vue) · [前端规范](../development/前端开发规范.md#工作台与治理总览)

**统计范围。** `admin:workbench:view` 控制入口。服务分别用 `audit:view` 和 `governance:field:view` 解析各自角色绑定的负责源。无该域可读来源时，相应的 `usage` 或 `feedback` 为 `null`。有权范围内的真实零记录返回计数 0。两域的回答分母可以不同。[接口](../../backend/DataOcean/src/main/java/com/dataocean/module/dashboard/controller/QueryExperienceController.java) · [范围与授权实现](../../backend/DataOcean/src/main/java/com/dataocean/module/dashboard/service/impl/QueryExperienceServiceImpl.java)

**时间与状态。** 仅统计 `IAM-SIMPLE-1` 的 `query_task`。按服务端本地时间的 `created_at` 左闭右开归组。今天按半小时分桶，昨天按小时分桶，近 7 天从六天前零点起按自然日分桶。今天与近 7 天都截止当前整秒 `asOf`。比较“昨日同时段”时，比较的是两个提问时间区间内的任务。状态取本次读取时的记录，失败、超时、取消、待澄清及进行中分别计数。

**速度和点踩。** 平均等候时间取已完成且 `completed_at >= created_at` 的任务，从 Java 受理计至服务端完成，精度为秒。用户屏幕首次显示或读到回答的时刻需要客户端另行采集。无可计时样本时均值和 P95 留空。整个区间的均值按任务加权，P95 取全部有效样本升序的第 `ceil(0.95 × 样本数)` 个。工作台以严格超过 10 秒为超目标回答。查询审计另有 Python 流程耗时和慢查询阈值。反馈按任务、本人和来源匹配当前有效票，点踩占比为踩数除以赞踩之和，评价覆盖的分母是反馈授权源内已完成的回答。当前没有独立业务抽检数据源，页面明确显示其可用状态。点踩数量和修复后的个案复验不能计算答案准确率。[统计与分页 SQL](../../backend/DataOcean/src/main/resources/mapper/dashboard/QueryExperienceMapper.xml) · [审计口径](011-audit-lineage.md#查询审计)

**明细重读。** `GET /api/admin/dashboard/query-experience/details` 要求携带总览返回的 `period` 和 `asOf`。`kind=slow` 还须提供该周期中的准确 `bucketStart`，慢回答按耗时降序列出标识、来源、提问时间和秒数。`kind=feedback` 使用整个周期，点踩明细可读取本域归因和用户备注。两条路径都重新判权。`asOf` 固定的是提问时间的截止点；随后完成的任务、后续改票和撤权都可能改变重读的结果或数量。摘要各项在同一只读事务内计算。

旧聚合接口 `GET /api/admin/dashboard/stats` 继续提供有权来源、发布资产、问题和近期版本活动的当前统计，保留原有调用合同。来源准备详情、审批队列以及告警规则维护在各自的业务入口中操作。工作台的超目标标色只反映当前统计区间，自动告警评估、去重和恢复仍见[后续开发](../development/后续开发.md)。[旧统计实现](../../backend/DataOcean/src/main/java/com/dataocean/module/dashboard/service/impl/DashboardServiceImpl.java) · [告警规则](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/AlertController.java)

## 测试与验证入口

- [OperationLogServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/impl/OperationLogServiceImplTest.java)：操作日志。
- [OperationLogAspectTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/aspect/OperationLogAspectTest.java)：日志切面。
- [NotificationRecipientResolverTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/NotificationRecipientResolverTest.java)：通知收件人解析。
- [Batch6CapabilityControl.test.ts](../../frontend/src/views/admin/operations/Batch6CapabilityControl.test.ts)：运营页面能力控制。

- [QueryExperienceServiceTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/dashboard/service/impl/QueryExperienceServiceTest.java) 使用隔离 H2 与正式 Mapper 校验问数口径、分页和分权。工作台的状态与交互见[首页回归](../../frontend/src/views/AdminHomeView.test.ts)、[身份与范围切换](../../frontend/src/views/WorkbenchLifecycle.test.ts)、[明细回归](../../frontend/src/views/admin/workbench/ExperienceDetailsView.test.ts)。

本轮没有定位到专门的 AiConfigServiceImpl 或 DashboardServiceImpl 单元测试文件，不能用上述日志/页面测试替代配置与统计验证。相关修改应补充定向测试；通知测试仅覆盖收件人解析，不覆盖全部已读操作。模型连通、配置重载、页面交互均需独立验收。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)。


## 操作审计前端

操作日志使用全局 `operation-log:view`，不借查询 audit 权限。详情取本次列表行，身份/IAM/筛选/刷新使旧详情与参数失效；历史参数按需转义展开，不承诺统一脱敏。成功仅表示调用未抛异常，HTTP 推断类型与路径首数字目标都不是业务结果证明。总数来自服务端，其余统计明确标注当前页，缺状态/耗时不补失败/零。URL 页大小、筛选和时间恢复一致，无效、缺一端或倒置时间需明确修改，不能退回全量。当前没有独立详情、导出或全量聚合 API。

## 平台工作面

`RuntimeView.vue` 的服务、SQL 池和规则 Tab 支持 URL 恢复。UNKNOWN/失败不能显示为健康，池重置仅 sys＋manage 并确认精确对象与原因。告警规则显示「仅支持配置」，启用不等于正在定时评估。

`AiConfig.vue` 区分 Chat/Embedding、active/pending、测试/同步与激活，密钥不回显；provider 归属未核对时不开放自动维度检测，不能用 Chat 写入绕过待切换 Embedding 的保护。

`PromptsView.vue` 保留场景、草稿、校验、版本、审核激活、回滚、日志和效果。维护/审核独立，保护精确版本和场景草稿；回滚创建新草稿，旧语义路由保留 query/hash 跳转。

通知只批读当前可见 ID，ACK 后重读真实全局计数，公开读写互斥；身份切换清浮层，旧回包失效，失败不显示为空或零。指南与 404 返回合法工作区或本人资料。认证及本人表单见[账号模块](001-user.md#账号页面)。
