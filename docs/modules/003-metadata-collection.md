# 003 元数据采集与资产目录

> 状态：现行模块说明；整理日期：2026-10-29。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 配套功能定位

关系图谱、影响分析和查询血缘的代码区别见[审计与血缘](011-audit-lineage.md)。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 自动采集计划 | [SyncScheduleController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/system/controller/SyncScheduleController.java)：`getSchedule`、`updateSchedule` | 全局计划，不是单数据源计划；更新后刷新调度器。 |

SyncSchedule.vue → SyncScheduleController → sys_config → AutoSyncScheduler。修改计划影响全部启用数据源，更新同时要求受保护 S1 系统管理员；运行方法见开发指南。前端只给系统管理员保存入口；`running` 表示调度已注册，不能解释为正在采集。预设不能无损表示的自定义 Cron 保留原串，空时间不发送无效表达式。已有 [Batch6ControllerAuthorizationTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/permission/s1/aspect/Batch6ControllerAuthorizationTest.java) 的 `globalScheduleWriteIsRejectedForNonSystemAdmin` 覆盖非系统管理员拒绝写入；它不覆盖非法 cron、关闭及刷新调度，后者仍需补核。血缘图谱入口见[查询审计与血缘](011-audit-lineage.md#实体血缘与图谱)。

## 修改定位

先按修改意图定位符号，再阅读下方行为约束。源码定位核对日期：2026-10-27；不代表运行验收。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 触发采集与读取快照详情 | [MetadataCollectionController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java)：`triggerSync`、`getSnapshotDetail`、`diffSnapshots` | API 归属检查与返回结构。 |
| 采集编排 | [SchemaCollectionServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service/impl/SchemaCollectionServiceImpl.java)：`executeFullSync`、`executeScheduledFullSync`、`doSyncAsync` | 手动/定时入口、异步任务与快照。 |
| 对象目录概览 | [CatalogOverviewServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service/impl/CatalogOverviewServiceImpl.java)：`overview` | 实体范围和详情汇总。 |

triggerSync → executeFullSync → 异步采集。目录测试不覆盖采集器：修改采集还需补核对应采集器的标识符、空值、只读访问和失败传播，不能据目录测试认定采集通过。

验证入口：[CatalogOverviewServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/metadata/service/impl/CatalogOverviewServiceImplTest.java)（目录概览）。本次只核对测试入口，修改实现后按[开发指南](../development/本地开发与验证.md)运行相关测试，再按影响范围补充接口或页面验证。

## 概览与主流程

Java 从选定数据源采集表、字段、索引、关系与统计，形成独立元数据快照，并提供差异、资产目录和实体关系。采集完成不等于已审核发布，也不自动激活 RAG。

数据源与授权检查 → schema_sync_task → Collector/JDBC 采集 → metadata_snapshot 及表字段 → 差异与目录 → [治理](004-metadata-governance.md) → [版本审核](005-metadata-versioning.md)。

目录与详情的来源、关联和状态边界在本文维护；交互见[前端规范](../development/前端开发规范.md)，未覆盖场景见[页面剩余验收](../development/后续开发.md#页面与角色边界)。

## 实现边界

- 采集编排和任务状态归 Java；采样值、统计与关系以实际快照为准，不将字段命名推断当成已审核业务事实。
- 采集通过默认执行器的 `@Async` 方法运行；返回任务 ID 不等于在执行器饱和时也能立即返回，具体边界见[异步执行约束](../技术栈与模块职责.md#54-执行约束)。
- `metadata_entity/metadata_relationship`承载实体、关系、术语、标签与血缘连接，FQN 标识资源；事件保留变更记录。
- catalog 搜索的 datasourceId 与分页范围在 SQL 内过滤，不能先分页再内存过滤。
- 已发布对象目录直接使用 `getPublishedCatalog` 返回的正式快照身份、版本与同步状态，不额外依赖要求 `datasource:view` 的准备情况接口。目录仍按本源 `metadata:view` 鉴权；没有正式快照、目录读取失败和无对象分别呈现，显式无权源不能展示另一源的旧目录。定向页面回归见 [AssetsViewMetadataCapability.test.ts](../../frontend/src/views/admin/assets/AssetsViewMetadataCapability.test.ts)。
- 手工 Join Path、术语和指标仍需来源与审核；收集到外键不代表所有业务 Join 已被批准。
- 数据资产域由对象目录、元数据版本和数据血缘（`/admin/assets/lineage`）组成；对象详情的血缘 Tab 以 `datasourceId + entityId` 深链到血缘工作台。采集关系与手工/ETL 血缘来源独立于质量检查。当前 S1 在同一受保护尝试完成后保存来源凭据及待处理标记，提交后独立事务写查询使用/输出来源索引并支持恢复。该索引不向参与 JOIN 的实体表之间生成数据流边。

来源可追溯性检查中的“缺外键/孤立表”、审核后的业务 Join 和实际数据流是不同证据。质量分、治理状态或业务关系变化不自动创建/改写血缘方向。查询血缘已按受保护输出来源接线，同源、幂等与恢复合同见[查询来源与投影合同](011-audit-lineage.md#提交后投影与恢复)。

## 对象与来源绑定

对象使用稳定实体 ID 保留人工关联，绑定同时校验实体类型、真实数据源、FQN 及精确来源快照。表 FQN 与字段 FQN 按各自身份解析，不能把数据库名误当表名，或把外源同名对象截成本源。

跨源实体血缘分别核验两端各自有效的发布快照；任端不存在、失效或无法绑定时保留 UNBOUND 与原因，以及原归属，不能降为全局对象。绑定成功不替代人工确认或知识审核。单 JVM 内的互斥锁不构成多实例并发保证，跨实例行为仍需独立验证；单次问数始终不因此取得跨库 JOIN 权限。

## 入口

| 代码 | 用途 |
| --- | --- |
| [MetadataCollectionController](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java) | `/api/admin/metadata`下 sync、sync-tasks、snapshots、tables、columns、diff |
| [MetadataCatalogController](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCatalogController.java) | 目录搜索与实体查询 |
| [metadata/collector](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/collector) | 表、列、索引、关系、统计采集 |
| [metadata/service](../../backend/DataOcean/src/main/java/com/dataocean/module/metadata/service) | 编排、快照、差异、实体关系和事件 |
| [CollectionsView](../../frontend/src/views/admin/metadata/CollectionsView.vue)、[AssetsView](../../frontend/src/views/admin/assets/AssetsView.vue) | 正式采集与资产工作区 |
| [metadata API](../../frontend/src/api/admin/metadata.ts)、[catalog API](../../frontend/src/api/admin/catalog.ts) | 前端接线 |

正式入口为 CollectionsView 与 AssetsView，TableExplorer.vue 由 AssetsView 内嵌使用，不是独立路由。数据结构以[迁移目录](../../backend/DataOcean/src/main/resources/db/migration)为准。

## 修改与验证

采集器变更检查标识符、动态 SQL、空值/采样和故障传播；目录变更检查 SQL 级范围及分页。发布、过期与 buildId 约束转到版本与 RAG 模块，不能在采集完成回调里自行切换知识索引。

## 前端 V2 范围与保护

采集任务的动作与跨区入口分别按目标源能力显示；发起后展示任务编号，有任务查看权时定位该源，否则交接有权人员。页面列表不是全源运行任务锁，后端继续裁决并发。正式目录/结构共用独立数据源范围区，显式无权源不回退偏好源，有权源可正常同步；历史快照URL不被公共初始化偏好覆盖。快照列表失败重试继续读取结构。

目录、结构和详情的返回身份以及账号/能力/范围代次均核验；对象取消标签确认和finally绑定原操作，防止同实体刷新后旧回调影响新写入。界面组织与已保留功能见[第三阶段映射](../development/前端第三阶段-采集与对象功能映射.md)，合成浏览器/测试证据及真实后端限制见[第三阶段验收](../acceptance/2026-10-29-采集与对象V2.md)。
