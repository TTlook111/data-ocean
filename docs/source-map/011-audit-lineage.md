# 011 查询审计与数据血缘

审计记录回答一次查询使用了哪些数据、产生多少行受保护结果。血缘模块分别管理查询实际使用的表和列，以及目录中维护的实体关系。

## 查询追溯

| 环节 | 现有行为 | 源码入口 |
| --- | --- | --- |
| 提交后生成追溯记录 | 任务结果先落库。监听器在事务提交后触发投影，定时扫描补处理仍为 `PENDING` 的任务。投影持任务行锁，复核同一任务的受保护尝试、SQL 哈希、权限修订、元数据快照及来源明细。写审计、写血缘和更新投影标记在一个独立事务内完成，失败时回滚后重试。 | [投影与事务](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/QueryTraceProjectionService.java) · [监听与恢复](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/QueryTraceProjectionWorker.java) |
| 查询审计 | 行数取自受保护结果数组。管理员可在有权的数据源范围查询执行记录、精确详情、慢查询和汇总统计。 | [审计接口](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/AuditLogController.java) · [写入、查询与统计](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/AuditLogServiceImpl.java) · [查询分析页](../../frontend/src/views/admin/audit/QueryAnalysisView.vue) |
| 表列使用与影响分析 | 将表使用、列使用和输出列来源写入查询血缘索引。影响分析按数据源、表及可选列统计已留存追溯的任务，按任务去重，并返回近期明细、覆盖起点和该源的待投影数。 | [读取接口](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/LineageController.java) · [索引写入与影响分析](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageServiceImpl.java) · [查询来源明细](../../frontend/src/views/admin/audit/QueryLineageEvidence.vue) |

## 目录实体关系

目录血缘支持按表或字段查看关系图，新增、批量新增和删除关系。增强图按起点、深度、类型和可见数据源展开。查询使用索引独立保存，因此表在一次 SQL 中一起出现不会自动生成一条加工关系。[关系管理接口](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/LineageEdgeController.java) · [写入与图遍历](../../backend/DataOcean/src/main/java/com/dataocean/module/audit/service/impl/LineageEdgeServiceImpl.java) · [血缘图页面](../../frontend/src/views/admin/audit/DataLineage.vue)

已确认的表级 `LINEAGE` 和字段级 `DERIVED_FROM` 可作为知识事实。知识服务用当前关系生成正文、完整依赖和来源指纹，后续校验可识别快照或端点变化导致的失效引用。[血缘事实与来源校验](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/LineageFactService.java)
