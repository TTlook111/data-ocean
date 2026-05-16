# Implementation Plan: 元数据采集模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-metadata-collection/spec.md`

## Summary

元数据采集模块负责从已接入的 MySQL 数据源中全量同步 Schema 信息（表、字段、索引、主外键、注释、统计信息），生成元数据快照。Java 层通过 JDBC DatabaseMetaData + information_schema 查询完成采集，支持手动触发和定时自动同步。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, JDBC (MySQL Connector/J), Spring Scheduling

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL)

**Target Platform**: Linux server (Docker)

**Project Type**: Web service (REST API) + 定时任务

**Performance Goals**: 100 张表全量同步 < 60s, 1000 张表 < 10min

**Constraints**: 采集使用只读连接，不影响业务库性能

**Scale/Scope**: MVP 阶段，单库最大支持 2000 张表

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | 采集是治理的第一步 |
| II. SQL 安全与只读执行 | ✅ PASS | 仅读取 information_schema，只读连接 |
| III. 三层分离架构 | ✅ PASS | Java 层完成采集，不涉及 Python |
| IV. RAG 准入控制 | ✅ PASS | 采集结果需经治理审核后才能进入 RAG |
| V. 可信度驱动生成 | N/A | 采集阶段不涉及可信度 |
| VI. 渐进式 MVP | ✅ PASS | 先全量同步，增量同步后续迭代 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/metadata/
├── controller/
│   └── MetadataCollectionController.java
├── service/
│   ├── SchemaCollectionService.java        # 核心采集逻辑
│   ├── SchemaSnapshotService.java          # 快照管理
│   ├── SchemaDiffService.java              # 快照差异对比
│   ├── SchemaSyncTaskService.java          # 同步任务管理
│   └── SchemaStatisticsService.java        # 字段统计采集
├── collector/
│   ├── TableCollector.java                 # 表信息采集
│   ├── ColumnCollector.java                # 字段信息采集
│   ├── IndexCollector.java                 # 索引采集
│   ├── RelationCollector.java              # 外键关系采集
│   └── StatisticsCollector.java            # 统计信息采集
├── mapper/
│   ├── MetadataSnapshotMapper.java
│   ├── DbTableMetaMapper.java
│   ├── DbColumnMetaMapper.java
│   ├── TableRelationMapper.java
│   ├── SchemaSyncTaskMapper.java
│   └── SchemaChangeEventMapper.java
├── entity/
│   ├── MetadataSnapshot.java
│   ├── DbTableMeta.java
│   ├── DbColumnMeta.java
│   ├── TableRelation.java
│   ├── SchemaSyncTask.java
│   └── SchemaChangeEvent.java
├── dto/
│   ├── SyncTriggerRequest.java
│   ├── SnapshotVO.java
│   ├── SchemaDiffVO.java
│   └── SyncTaskVO.java
└── scheduler/
    └── AutoSyncScheduler.java              # 定时同步调度

backend/src/main/resources/db/migration/
└── V4__create_metadata_tables.sql
```

## Implementation Phases

### Phase 1: 数据模型 + 快照框架

1. 创建数据库表（Flyway V4）
2. 实现所有实体类和 Mapper
3. 实现 SchemaSnapshotService（快照创建、状态管理）
4. 实现 SchemaSyncTaskService（任务状态跟踪）

### Phase 2: 核心采集逻辑

1. 实现 TableCollector（JDBC DatabaseMetaData.getTables）
2. 实现 ColumnCollector（DatabaseMetaData.getColumns + information_schema.COLUMNS）
3. 实现 IndexCollector（DatabaseMetaData.getIndexInfo）
4. 实现 RelationCollector（DatabaseMetaData.getImportedKeys/getExportedKeys）
5. 实现 SchemaCollectionService 编排所有 Collector

### Phase 3: 统计信息采集

1. 实现 StatisticsCollector（行数估算 via information_schema.TABLES）
2. 字段空值率采集（SELECT COUNT(*) WHERE col IS NULL，采样模式）
3. 枚举值 TopN 采集（SELECT col, COUNT(*) GROUP BY col LIMIT 20）

### Phase 4: 差异对比 + 变更事件

1. 实现 SchemaDiffService（新旧快照对比）
2. 生成 SchemaChangeEvent（新增表/删除表/字段变更等）
3. 变更事件分级（高风险：删除表/字段，中风险：类型变更，低风险：注释变更）

### Phase 5: 定时同步 + API

1. 实现 AutoSyncScheduler（@Scheduled，可配置 cron）
2. 实现 MetadataCollectionController
3. 集成测试

## Key Technical Decisions

- 分批采集：每批 100 张表，避免单次查询过大
- 临时连接：每次采集创建临时 JDBC 连接，用完关闭，不维护连接池
- Schema Hash：对快照内容计算 MD5，用于快速判断是否有变更
- 统计采集可选：字段统计（空值率、TopN）耗时较长，作为可选步骤

## Complexity Tracking

无违规项。
