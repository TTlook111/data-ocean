# 003 元数据采集与资产目录

旧版按数据源采集 MySQL 元数据，记录采集任务和快照，供资产目录、治理及版本审核读取。正式发布由[元数据版本模块](005-metadata-versioning.md)处理。

## 手动采集与调度

1. 后台发起采集并选择是否采集统计信息。服务校验来源并创建 `schema_sync_task`，异步读取表、字段、索引和关系，落库采集结果，更新任务状态。[采集接口](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java) · [采集编排](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service/impl/SchemaCollectionServiceImpl.java) · [采集器](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/collector)
2. 每次采集将表和字段绑定到 `metadata_snapshot`。可读取任务、快照和对象详情，对比两个快照的结构差异。[快照与任务对象](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/entity/po) · [采集工作区](../../frontend/src/views/admin/metadata/CollectionsView.vue)
3. 系统管理员可配置全局自动采集计划。调度器对启用的数据源触发采集，手动和定时任务共用快照生成逻辑。[计划配置](../../backend/DataOcean/src/main/java/com/dataocean/module/system/controller/SyncScheduleController.java) · [调度设置](../../frontend/src/views/admin/metadata/SyncSchedule.vue)

## 资产目录

目录按数据源和权限筛选对象，读取已发布快照的身份和版本，并通过实体 ID、类型和全限定名定位表或字段。实体、关系和变更事件分别保存。采集到的外键属于源结构，业务关系的审核与[查询血缘](011-audit-lineage.md)各有独立来源。[目录接口](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCatalogController.java) · [目录概览](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service/impl/CatalogOverviewServiceImpl.java) · [对象目录](../../frontend/src/views/admin/assets/AssetsView.vue)
