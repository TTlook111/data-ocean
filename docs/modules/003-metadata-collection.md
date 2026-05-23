# 003 元数据采集

## 概述
元数据采集模块负责从业务数据源中自动采集数据库结构信息（表、字段、索引、外键关系、统计信息），生成元数据快照，为后续的元数据治理、Schema RAG 和 SQL 生成提供数据基础。

## 解决的问题
- 自动化采集业务库的表结构、字段信息、索引和外键关系
- 采集字段级统计信息（空值率、去重计数、TopN 值）用于可信度评估
- 快照化管理：每次采集生成独立快照，支持版本对比
- 增量变更检测：对比两个快照发现新增/删除/修改的表和字段

## 实现方案
- **采集器模式**: 5 个独立 Collector 组件分别负责表、字段、索引、关系、统计信息的采集
- **JDBC 直连**: 通过 INFORMATION_SCHEMA 查询元数据，不依赖特定 MySQL 版本
- **快照机制**: 每次全量采集生成新快照（metadata_snapshot），关联所有采集到的表和字段记录
- **异步执行**: 采集任务异步执行，通过 schema_sync_task 跟踪进度和状态
- **差异对比**: SchemaDiffService 对比两个快照，输出表级和字段级的变更明细

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java`
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/metadata/service/`
  - `SchemaCollectionService.java` / `impl/SchemaCollectionServiceImpl.java` — 采集编排
  - `SchemaSyncTaskService.java` / `impl/SchemaSyncTaskServiceImpl.java` — 任务管理
  - `SchemaSnapshotService.java` / `impl/SchemaSnapshotServiceImpl.java` — 快照管理
  - `SchemaDiffService.java` / `impl/SchemaDiffServiceImpl.java` — 差异对比
  - `SchemaStatisticsService.java` / `impl/SchemaStatisticsServiceImpl.java` — 统计采集
- Collector: `backend/DataOcean/src/main/java/com/dataocean/module/metadata/collector/`
  - `TableCollector.java` — 表信息采集
  - `ColumnCollector.java` — 字段信息采集
  - `IndexCollector.java` — 索引信息采集
  - `RelationCollector.java` — 外键/推断关系采集
  - `StatisticsCollector.java` — 字段统计信息采集
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/metadata/entity/`
  - `DbTableMeta.java`, `DbColumnMeta.java`, `TableRelation.java`, `SchemaSyncTask.java`, `SchemaChangeEvent.java`, `MetadataSnapshot.java`

### 前端
- 页面:
  - `frontend/src/views/admin/metadata/SyncTask.vue` — 同步任务列表
  - `frontend/src/views/admin/metadata/SnapshotList.vue` — 快照列表
  - `frontend/src/views/admin/metadata/TableExplorer.vue` — 表浏览器
  - `frontend/src/views/admin/metadata/SnapshotDiff.vue` — 快照差异对比
  - `frontend/src/views/admin/metadata/SyncSchedule.vue` — 同步调度配置
- API: `frontend/src/api/admin/metadata.ts`

### 数据库
- 迁移脚本: `V7__create_metadata_tables.sql`, `V8__add_metadata_permission.sql`, `V9__add_table_indexes_info.sql`
- 涉及表: `db_table_meta`, `db_column_meta`, `table_relation`, `schema_sync_task`, `schema_change_event`, `metadata_snapshot`

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| POST | `/api/admin/metadata/sync` | 触发元数据同步 | 前端同步任务页 |
| GET | `/api/admin/metadata/sync-tasks` | 查询同步任务列表 | 前端同步任务页 |
| GET | `/api/admin/metadata/snapshots` | 查询快照列表 | 前端快照列表页 |
| GET | `/api/admin/metadata/snapshots/{id}` | 快照详情 | 前端表浏览器 |
| GET | `/api/admin/metadata/snapshots/{id}/tables` | 快照中的表列表 | 前端表浏览器/治理页 |
| GET | `/api/admin/metadata/snapshots/{id}/tables/{tableName}/columns` | 表的字段列表 | 前端表浏览器/治理页 |
| GET | `/api/admin/metadata/snapshots/diff` | 快照差异对比 | 前端差异对比页 |

## 模块间依赖
- **被依赖**: 004 元数据治理（基于快照做质量检查）、005 版本审核（管理快照生命周期）、006 知识库（基于快照生成 skills.md）
- **依赖**: 002 数据源管理（获取连接信息）
