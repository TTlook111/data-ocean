# 查询审计与数据血缘

> 状态：现行模块与代码导航；核对日期：2026-10-28。行为边界与定位在此维护，运行结论见[项目现状](../项目现状.md)及[验收索引](../acceptance/README.md)。
>
> 返回[功能导航](README.md)。本页不授权执行配置写入、供应商测试或数据变更。

## 查询审计

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 审计写入、查询、慢查询与统计 | [AuditLogServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/AuditLogServiceImpl.java)：`recordCommittedAudit`、`listAuditLogsInDatasources`、`getAuditLogDetail`、`listSlowQueriesInDatasources`、`getStatsInDatasources` | 负责源裁剪、分页与真实统计口径。 |

查询结果处理与审计接口分别接入 AuditLogServiceImpl；修改统计前核对 S1 与历史任务的实际口径。模型返回成功不代表 SQL、业务结果或人工验收成功。后台操作日志另见[平台模块](010-platform-operations.md#通知与操作日志)。

提交审计的 row_count 由同任务已保护结果数组计算，空数组记录 0；缺失、格式错误或非数组不记作成功空集，投影事务失败后保留待恢复状态。旧行数缺失记录不自动回填为已验证证据。系统联调另核对 API 数量/实际数组、PROTECTED 数量/数组与审计数量一致。

## 实体血缘与图谱

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 新增、删除、批量与增强图谱 | [LineageEdgeServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageEdgeServiceImpl.java)：`createLineage`、`deleteLineage`、`batchCreateLineage`、`getEnrichedLineage` | 实体归属、关系类型、级联与范围。 |
| 图谱加载、节点与深链 | [DataLineage.vue](../../frontend/src/views/admin/audit/DataLineage.vue)：`loadGraph`、`loadEntityLineage`、`syncFocusToUrl`、`handleDeleteEdge`、`handleAnalyzeImpact` | datasourceId + entityId、范围切换与请求结果。 |
| 表/列查询血缘与影响分析 | [LineageServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageServiceImpl.java)：`saveProtectedLineage`、`queryTableLineage`、`queryColumnLineage`、`analyzeImpact` | 按受保护尝试写查询使用与输出来源索引，不桥接物理数据流。 |

DataLineage.vue 组合实体增强血缘与相关查询接口；LineageEdgeController 管理关系写入，LineageController 提供查询血缘读取/影响分析。先按关系来源选链路，不能把两套存储入口当作同一流程。

S1 的[QueryTraceProjectionService](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/QueryTraceProjectionService.java)持任务行锁，在提交后独立事务中写审计、使用/输出来源索引及完成标记。[QueryTraceProjectionWorker](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/QueryTraceProjectionWorker.java)监听提交后通知并有限批次恢复未完成任务；旧 columnDerivations/FROM→JOIN 桥接入口已移除。来源、任务/尝试/hash、幂等及事务边界见[适配分析](../development/RAG与问数治理适配问题.md)。

影响分析按可见数据源和表/列名统计已完成追溯的成功受保护 S1 任务，独立 COUNT(DISTINCT task) 不受 100 行明细上限影响，近期任务去重。响应包含最早记录、当前数据源待处理数量和覆盖提示；没有快照/时间过滤，未补证旧记录不计入，零记录不表示没有影响。主图和实体展开统一接管请求及 loading，旧增强/回退结果与失败不覆盖当前范围；影响读取也隔离旧响应。[隔离验证](../acceptance/2026-10-27-治理下游适配修复.md)不能替代真实页面及业务验收。

## 测试与验证入口

- [AuditLogServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/audit/service/impl/AuditLogServiceImplTest.java)：查询审计。
- [LineageEdgeServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/audit/service/impl/LineageEdgeServiceImplTest.java)：实体关系写入与图谱。
- [DataLineage.test.ts](../../frontend/src/views/admin/audit/DataLineage.test.ts)：血缘界面。

修改后分别验证源范围、实体 ID/字段 ID 区别、关系删除、空图与失败提示。[QueryTraceProjectionIntegrationTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/audit/service/impl/QueryTraceProjectionIntegrationTest.java)另覆盖真实 H2/Spring 事务代理下的提交、回滚、并发幂等、恢复和超过 100 个任务的统计。实际 MySQL、模型、权限变更及浏览器闭环仍分别验收。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)。

