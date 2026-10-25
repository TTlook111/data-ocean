# 003 元数据采集与资产目录

> 状态：现行模块说明；整理日期：2026-10-25。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 概览与主流程

Java 从选定数据源采集表、字段、索引、关系与统计，形成独立元数据快照，并提供差异、资产目录和实体关系。采集完成不等于已审核发布，也不自动激活 RAG。

数据源与授权检查 → schema_sync_task → Collector/JDBC 采集 → metadata_snapshot 及表字段 → 差异与目录 → [治理](004-metadata-governance.md) → [版本审核](005-metadata-versioning.md)。

目录与详情的主体完善方案已实施并[归档](../archive/plans/completed/数据对象目录与详情完善方案.md)。当前来源、关联和状态边界继续按本模块及[前端规范](../development/前端开发规范.md)维护；未完成的人工验收和边界复核见[后续开发第 7 项](../development/后续开发.md#7-数据对象目录的剩余验收与边界复核)，不以归档替代验收。

## 实现边界

- 采集编排和任务状态归 Java；采样值、统计与关系以实际快照为准，不将字段命名推断当成已审核业务事实。
- 采集通过默认执行器的 `@Async` 方法运行；返回任务 ID 不等于在执行器饱和时也能立即返回，具体边界见[异步执行约束](../技术栈与模块职责.md#54-执行约束)。
- `metadata_entity/metadata_relationship`承载实体、关系、术语、标签与血缘连接，FQN 标识资源；事件保留变更记录。
- catalog 搜索的 datasourceId 与分页范围在 SQL 内过滤，不能先分页再内存过滤。
- 手工 Join Path、术语和指标仍需来源与审核；收集到外键不代表所有业务 Join 已被批准。
- 数据资产域由对象目录、元数据版本和数据血缘（`/admin/assets/lineage`）组成；对象详情的血缘 Tab 以 `datasourceId + entityId` 深链到血缘工作台。采集关系与手工/ETL 血缘来源独立于质量检查。当前 S1 保存查询结果来源，但到查询血缘存储的写入尚未接通，不能宣称问数已自动更新血缘图；见[联动复核](../acceptance/2026-10-25-语义中心复核记录.md)。

来源可追溯性检查中的“缺外键/孤立表”、审核后的业务 Join 和实际数据流是不同证据。质量分、治理状态或业务关系变化不自动创建/改写血缘方向。查询血缘需按受保护输出来源补接线、同源与幂等验证，问题分析见[RAG 与问数治理适配问题](../development/RAG与问数治理适配问题.md)。

## 入口

| 代码 | 用途 |
| --- | --- |
| [MetadataCollectionController](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java) | `/api/admin/metadata`下 sync、sync-tasks、snapshots、tables、columns、diff |
| [MetadataCatalogController](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCatalogController.java) | 目录搜索与实体查询 |
| [metadata/collector](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/collector) | 表、列、索引、关系、统计采集 |
| [metadata/service](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service) | 编排、快照、差异、实体关系和事件 |
| [CollectionsView](../../frontend/src/views/admin/metadata/CollectionsView.vue)、[AssetsView](../../frontend/src/views/admin/assets/AssetsView.vue) | 正式采集与资产工作区 |
| [metadata API](../../frontend/src/api/admin/metadata.ts)、[catalog API](../../frontend/src/api/admin/catalog.ts) | 前端接线 |

旧独立 SnapshotList/TableExplorer 等页面路径不作为当前入口。字段采样由 V45、实体关系由 V37 等历史迁移引入；完整数据结构以[迁移目录](../../backend/DataOcean/src/main/resources/db/migration)为准。

## 修改与验证

采集器变更检查标识符、动态 SQL、空值/采样和故障传播；目录变更检查 SQL 级范围及分页。发布、过期与 buildId 约束转到版本与 RAG 模块，不能在采集完成回调里自行切换知识索引。
