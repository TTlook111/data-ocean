# 查询审计与数据血缘

> 状态：现行模块与代码导航；核对日期：2026-10-26。行为边界与定位在此维护，运行结论见[项目现状](../项目现状.md)及[验收索引](../acceptance/README.md)。
>
> 返回[功能导航](README.md)。本页不授权执行配置写入、供应商测试或数据变更。

## 查询审计

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 审计写入、查询、慢查询与统计 | [AuditLogServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/AuditLogServiceImpl.java)：`recordAudit`、`listAuditLogsInDatasources`、`getAuditLogDetail`、`listSlowQueriesInDatasources`、`getStatsInDatasources` | 负责源裁剪、分页与真实统计口径。 |

查询结果处理与审计接口分别接入 AuditLogServiceImpl；修改统计前核对 S1 与历史任务的实际口径。模型返回成功不代表 SQL、业务结果或人工验收成功。后台操作日志另见[平台模块](010-platform-operations.md#通知与操作日志)。

## 实体血缘与图谱

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 新增、删除、批量与增强图谱 | [LineageEdgeServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageEdgeServiceImpl.java)：`createLineage`、`deleteLineage`、`batchCreateLineage`、`getEnrichedLineage` | 实体归属、关系类型、级联与范围。 |
| 图谱加载、节点与深链 | [DataLineage.vue](../../frontend/src/views/admin/audit/DataLineage.vue)：`loadGraph`、`loadEntityLineage`、`syncFocusToUrl`、`handleDeleteEdge`、`handleAnalyzeImpact` | datasourceId + entityId、范围切换与请求结果。 |
| 表/列查询血缘与影响分析 | [LineageServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageServiceImpl.java)：`saveLineage`、`queryTableLineage`、`queryColumnLineage`、`analyzeImpact` | 这是查询血缘存储/读取能力，不等于 S1 已自动写入。 |

DataLineage.vue 组合实体增强血缘与相关查询接口；LineageEdgeController 管理关系写入，LineageController 提供查询血缘读取/影响分析。先按关系来源选链路，不能把两套存储入口当作同一流程。

当前 S1 保存受保护结果来源，但到查询血缘存储的接线仍有缺口，见[采集与资产边界](003-metadata-collection.md)及[治理适配问题](../development/RAG与问数治理适配问题.md)。不得通过旧 columnDerivations 写入口假装完成适配；治理检查、业务 Join 与真实数据流是不同证据。

## 测试与验证入口

- [AuditLogServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/audit/service/impl/AuditLogServiceImplTest.java)：查询审计。
- [LineageEdgeServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/audit/service/impl/LineageEdgeServiceImplTest.java)：实体关系写入与图谱。
- [DataLineage.test.ts](../../frontend/src/views/admin/audit/DataLineage.test.ts)：血缘界面。

修改后分别验证源范围、实体 ID/字段 ID 区别、关系删除、空图与失败提示。上述测试不能证明受保护查询到血缘写入的闭环已成立；同源、幂等与完整来源仍按实际接线专项验证。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)。

