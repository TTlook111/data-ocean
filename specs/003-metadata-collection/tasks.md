# Tasks: 元数据采集模块

**Input**: Design documents from `specs/003-metadata-collection/`
**Prerequisites**: plan.md, spec.md, data-model.md

## Phase 1: Setup

- [X] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V4__create_metadata_tables.sql`，包含 metadata_snapshot、db_table_meta、db_column_meta、table_relation、schema_sync_task、schema_change_event 六张表的 DDL，含索引和外键约束

## Phase 2: Foundational — 实体与 Mapper

- [X] T002 [P] 创建快照实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/MetadataSnapshot.java`，映射 metadata_snapshot 表，包含 publish_status 枚举（DRAFT/CHECKING/ISSUE_FOUND/APPROVED/PUBLISHED/EXPIRED）
- [X] T003 [P] 创建表元数据实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/DbTableMeta.java`，映射 db_table_meta 表，包含 governance_status 和 review_status 字段
- [X] T004 [P] 创建字段元数据实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/DbColumnMeta.java`，映射 db_column_meta 表
- [X] T005 [P] 创建表关联关系实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/TableRelation.java`
- [X] T006 [P] 创建同步任务实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/SchemaSyncTask.java`
- [X] T007 [P] 创建变更事件实体类 `backend/src/main/java/com/dataocean/module/metadata/entity/SchemaChangeEvent.java`
- [X] T008 [P] 创建所有 Mapper 接口：`backend/src/main/java/com/dataocean/module/metadata/mapper/MetadataSnapshotMapper.java`、`DbTableMetaMapper.java`、`DbColumnMetaMapper.java`、`TableRelationMapper.java`、`SchemaSyncTaskMapper.java`、`SchemaChangeEventMapper.java`

## Phase 3: User Story 1 (P1) — 全量元数据同步

**Goal**: 管理员触发全量同步，系统读取数据库所有表、字段、索引、主外键信息并生成快照
**Independent Test**: 同步完成后，系统中能查看到该数据源的所有表和字段信息，且与实际数据库一致

- [X] T009 [US1] 创建同步任务服务 `backend/src/main/java/com/dataocean/module/metadata/service/SchemaSyncTaskService.java`，实现：createTask(datasourceId, syncType)、updateTaskStatus(taskId, status, errorMessage)、getLatestTask(datasourceId)
- [X] T010 [US1] 创建快照服务 `backend/src/main/java/com/dataocean/module/metadata/service/SchemaSnapshotService.java`，实现：createSnapshot(datasourceId, taskId)（生成快照编号、初始状态 DRAFT）、updateSnapshotStats(snapshotId, tableCount, columnCount, schemaHash)、getPublishedSnapshot(datasourceId)
- [X] T011 [US1] 创建表信息采集器 `backend/src/main/java/com/dataocean/module/metadata/collector/TableCollector.java`，通过 JDBC DatabaseMetaData.getTables() 读取所有用户表（排除系统表），返回表名、表类型、表注释列表，分批处理每批 100 张表
- [X] T012 [US1] 创建字段信息采集器 `backend/src/main/java/com/dataocean/module/metadata/collector/ColumnCollector.java`，通过 DatabaseMetaData.getColumns() + information_schema.COLUMNS 读取字段名、数据类型、注释、是否可空、默认值、是否主键
- [X] T013 [US1] 创建索引采集器 `backend/src/main/java/com/dataocean/module/metadata/collector/IndexCollector.java`，通过 DatabaseMetaData.getIndexInfo() 读取索引信息
- [X] T014 [US1] 创建外键关系采集器 `backend/src/main/java/com/dataocean/module/metadata/collector/RelationCollector.java`，通过 DatabaseMetaData.getImportedKeys()/getExportedKeys() 读取外键关系，存入 table_relation 表
- [X] T015 [US1] 创建核心采集编排服务 `backend/src/main/java/com/dataocean/module/metadata/service/SchemaCollectionService.java`，实现 executeFullSync(datasourceId) 方法：创建同步任务 → 获取数据源连接信息（调用 002 模块解密密码）→ 创建临时 JDBC 连接 → 依次调用 TableCollector、ColumnCollector、IndexCollector、RelationCollector → 批量保存结果 → 计算 Schema Hash → 创建快照 → 更新任务状态
- [X] T016 [US1] 创建同步触发请求 DTO `backend/src/main/java/com/dataocean/module/metadata/dto/SyncTriggerRequest.java`，字段：datasourceId、includeStatistics（是否采集统计信息，默认 false）
- [X] T017 [US1] 创建快照视图对象 `backend/src/main/java/com/dataocean/module/metadata/dto/SnapshotVO.java`，字段：id、snapshotNo、datasourceName、tableCount、columnCount、qualityScore、publishStatus、createdAt
- [X] T018 [US1] 创建同步任务视图对象 `backend/src/main/java/com/dataocean/module/metadata/dto/SyncTaskVO.java`，字段：id、datasourceName、syncType、status、startedAt、finishedAt、errorMessage

## Phase 4: 统计信息采集

- [X] T019 [P] 创建统计信息采集器 `backend/src/main/java/com/dataocean/module/metadata/collector/StatisticsCollector.java`，实现：collectRowCount(connection, tableName)（通过 information_schema.TABLES 的 TABLE_ROWS 估算）、collectNullRate(connection, tableName, columnName)（SELECT COUNT(*) WHERE col IS NULL / COUNT(*) 采样 LIMIT 1000）、collectTopNValues(connection, tableName, columnName, n)（SELECT col, COUNT(*) GROUP BY col ORDER BY COUNT(*) DESC LIMIT n）
- [X] T020 创建统计采集服务 `backend/src/main/java/com/dataocean/module/metadata/service/SchemaStatisticsService.java`，实现 collectStatistics(datasourceId, snapshotId) 方法，遍历所有表和字段调用 StatisticsCollector，将结果更新到 db_table_meta.row_count_estimate 和 db_column_meta 的统计字段

## Phase 5: User Story 3 (P2) — 快照差异对比

**Goal**: 管理员对比两次快照的差异，了解数据库结构变更
**Independent Test**: 在数据库中新增一张表后重新同步，差异报告中能看到该新增表

- [X] T021 [US3] 创建差异对比视图对象 `backend/src/main/java/com/dataocean/module/metadata/dto/SchemaDiffVO.java`，字段：addedTables、removedTables、addedColumns、removedColumns、modifiedColumns（含 oldType/newType、oldComment/newComment）
- [X] T022 [US3] 创建差异对比服务 `backend/src/main/java/com/dataocean/module/metadata/service/SchemaDiffService.java`，实现 compareSnapshots(oldSnapshotId, newSnapshotId) 方法：加载两个快照的表和字段数据，按表名匹配对比，输出新增表、删除表、新增字段、删除字段、类型变更、注释变更
- [X] T023 [US3] 在 SchemaDiffService 中实现变更事件生成：对比结果写入 schema_change_event 表，按风险分级（HIGH: 删除表/字段, MEDIUM: 类型变更, LOW: 注释变更）

## Phase 6: User Story 2 (P2) — 定时自动同步

**Goal**: 系统每天凌晨自动执行元数据同步
**Independent Test**: 定时任务触发后，新快照与数据库当前状态一致

- [X] T024 [US2] 创建定时同步调度器 `backend/src/main/java/com/dataocean/module/metadata/scheduler/AutoSyncScheduler.java`，使用 @Scheduled(cron) 注解，cron 表达式从 application.yml 配置读取（默认每天凌晨 2 点），遍历所有启用状态的数据源执行全量同步
- [X] T025 [US2] 在 application.yml 中添加定时同步配置项 `dataocean.metadata.auto-sync.cron` 和 `dataocean.metadata.auto-sync.enabled`

## Phase 7: API 层

- [X] T026 创建元数据采集控制器 `backend/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java`，实现：POST /api/admin/metadata/sync（触发全量同步）、GET /api/admin/metadata/sync-tasks（同步任务列表）、GET /api/admin/metadata/snapshots（快照列表）、GET /api/admin/metadata/snapshots/{id}（快照详情含表和字段）、GET /api/admin/metadata/snapshots/diff（两个快照对比，参数 oldId 和 newId）

## Phase 9: Sync Scheduling UI

- [X] T032 [Frontend] 创建同步调度配置页面 `frontend/src/views/admin/metadata/SyncSchedule.vue`：配置同步频率（每天/每周/自定义 cron）、同步时间、启用/禁用
- [X] T033 在 Java 后端添加 sys_config 表和 ConfigService，支持在线修改同步 cron 表达式（无需重启）

## Dependencies

```
T001 → T002-T008 (表结构先于实体)
T002-T008 → T009, T010 (实体先于 Service)
T009, T010 → T015 (任务和快照服务先于编排服务)
T011-T014 → T015 (各 Collector 先于编排服务)
T015 → T024 (同步逻辑先于定时调度)
T010 → T022 (快照服务先于差异对比)
T015, T022 → T026 (Service 层先于 Controller)
T019 → T020 (统计采集器先于统计服务)
```

## Implementation Strategy

MVP-first: Phase 3（全量同步核心）是最高优先级，确保能从数据库采集完整的 Schema 信息。统计信息采集（Phase 4）作为可选增强，差异对比（Phase 5）和定时同步（Phase 6）可并行开发。采集器采用策略模式，每个 Collector 独立可测试，由 SchemaCollectionService 统一编排。

## Phase 8: Frontend Pages

- [X] T027 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/metadata.ts`：触发同步、查看任务状态、快照列表、表详情、差异对比
- [X] T028 [Frontend] 创建同步任务页面 `frontend/src/views/admin/metadata/SyncTask.vue`：触发全量同步按钮、任务进度展示、历史任务列表
- [X] T029 [Frontend] 创建快照列表页面 `frontend/src/views/admin/metadata/SnapshotList.vue`：快照编号/状态/表数量/质量分/创建时间
- [X] T030 [Frontend] 创建表浏览器页面 `frontend/src/views/admin/metadata/TableExplorer.vue`：左侧表列表、右侧字段详情（类型/注释/空值率/索引）
- [X] T031 [Frontend] 创建快照差异页面 `frontend/src/views/admin/metadata/SnapshotDiff.vue`：选择两个快照对比，展示新增/删除/变更的表和字段
