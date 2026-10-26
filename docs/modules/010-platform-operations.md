# 平台配置、通知与工作台

> 状态：现行模块与代码导航；核对日期：2026-10-26。行为边界与定位在此维护，运行结论见[项目现状](../项目现状.md)及[验收索引](../acceptance/README.md)。
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
| 告警规则维护 | [AlertController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/AlertController.java)：`listRules`、`createRule`、`updateRule`、`toggleRule` | 这里只定位规则 CRUD；不证明告警执行、历史与通知链已完整接通。 |

DashboardController.getStats → DashboardServiceImpl.getStats；工作台待办与展示规则见[前端规范](../development/前端开发规范.md#任务与渐进式呈现)。不能由工作台入口权限推导所有卡片数据权限，读取失败也不能显示成 0。

告警执行与历史的完成程度沿[后续开发](../development/后续开发.md)核对，不因规则接口存在宣布告警已完成。

## 测试与验证入口

- [OperationLogServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/impl/OperationLogServiceImplTest.java)：操作日志。
- [OperationLogAspectTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/aspect/OperationLogAspectTest.java)：日志切面。
- [NotificationRecipientResolverTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/system/service/NotificationRecipientResolverTest.java)：通知收件人解析。
- [Batch6CapabilityControl.test.ts](../../frontend/src/views/admin/operations/Batch6CapabilityControl.test.ts)：运营页面能力控制。

本轮没有定位到专门的 AiConfigServiceImpl 或 DashboardServiceImpl 单元测试文件，不能用上述日志/页面测试替代配置与统计验证。相关修改应补充定向测试；通知测试仅覆盖收件人解析，不覆盖全部已读操作。模型连通、配置重载、页面交互均需独立验收。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)。

