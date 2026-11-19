# 源码定位

这里定位当前正式实现与仓库保留的其他系统代码，供开发、运行维护和复用评估。业务与设计依据分别见[产品方案](../product/README.md)、[前端开发入口](../frontend/README.md#开发前必读与完成验收)、[后端设计](../backend/README.md)和[系统架构](../architecture/README.md)，不能用源码偏差改写已确认规则。

说明必须与仍存在的源码对应。源码发生已授权变更时同步路径和事实；已无用途的说明直接删除，不维护过期区。

## 首批正式实现

| 范围 | 实际入口 |
| --- | --- |
| React 路由、登录边界与后台外壳 | [应用装配](../../frontend/src/app/Application.tsx)、[启动与 Provider](../../frontend/src/app/main.tsx) |
| 前端主题与公共样式 | [Ant Design 主题](../../frontend/src/app/theme.ts)、[布局与状态样式](../../frontend/src/app/styles.css)；尺度依据见[界面规范](../frontend/界面规范.md) |
| 登录与首次改密、账号、人员、部门、角色配置及权限审计页面 | [IAM 页面](../../frontend/src/modules/iam/)；覆盖与验收边界见[前端实现状态](../frontend/README.md#当前实现与验证边界) |
| 前端请求、会话、表格、字段错误及操作核对 | [共用代码](../../frontend/src/shared/)；短任务对象摘要在 `OperationContext.tsx`，服务端字段错误定位在 `formErrors.ts` |
| Java 统一返回、异常、分页与身份上下文 | [公共代码](../../backend/src/main/java/com/dataocean/common/)、[分页配置](../../backend/src/main/java/com/dataocean/infrastructure/persistence/MybatisConfig.java)；复用要求见[Java 细则](../backend/编码规范.md#公共代码复用) |
| Java 认证、人员、部门、角色与权限 | [IAM 模块](../../backend/src/main/java/com/dataocean/module/iam/)；接口设计与验收见[首批模块入口](../backend/详细设计与验收.md) |
| Java 审计及身份建库基线 | [审计模块](../../backend/src/main/java/com/dataocean/module/audit/)、[建库脚本](../../backend/src/main/resources/db/migration/) |
| Python 正式公共包、配置与存活 API | [API](../../python-service/src/dataocean_ai/api/app.py)、[公共配置／返回／日志](../../python-service/src/dataocean_ai/core/)、[构建与运行](../../python-service/README.md)；旧 `dataocean` 不进入正式产物，工作流执行器按业务批次接入 |

以上仅说明已存在的源码位置，不代表逐接口、界面或跨端链路全部验收通过。离线原型的定位仍由[页面专题](../frontend/pages/README.md#原型定位与衔接)维护。

## 数据源接入与采集

| 范围 | 入口 |
| --- | --- |
| 数据源管理、采集记录、目录与结构变化 | [React 数据源模块](../../frontend/src/modules/datasource/) |
| 连接启用、分项测试、配置与凭据替换 | [数据源接口](../../backend/src/main/java/com/dataocean/module/datasource/controller/DatasourceController.java) |
| 采集接收、状态、停止与恢复、目录提交 | [采集接口](../../backend/src/main/java/com/dataocean/module/datasource/controller/CollectionController.java)、[采集工作者](../../backend/src/main/java/com/dataocean/module/datasource/service/CollectionRunner.java)、[快照提交](../../backend/src/main/java/com/dataocean/module/datasource/service/CatalogPublication.java) |
| 双库适配、采集计划及平台库表 | [源库适配](../../backend/src/main/java/com/dataocean/module/datasource/source/SourceJdbc.java)、[定时](../../backend/src/main/java/com/dataocean/module/datasource/service/DatasourceScheduler.java)、[Flyway V3](../../backend/src/main/resources/db/migration/V3__datasource_collection.sql) |
| 企业业务说明与历史原件 | [资料 API](../../backend/src/main/java/com/dataocean/module/datasource/controller/BusinessNoteController.java)、[React 页面](../../frontend/src/modules/datasource/BusinessNotesPage.tsx)、[资料表](../../backend/src/main/resources/db/migration/V6__business_notes.sql)、[原件容量](../../backend/src/main/resources/db/migration/V8__business_note_file_limits.sql) |

真实覆盖见[数据源验收](../backend/datasource/验收.md#当前验证范围)。

## 数据访问授权

| 范围 | 入口 |
| --- | --- |
| 审批责任、输出限制和直属部门配置 | [接口](../../backend/src/main/java/com/dataocean/module/access/controller/AccessController.java)、[配置实现](../../backend/src/main/java/com/dataocean/module/access/service/impl/AccessConfigurationServiceImpl.java) |
| 本人申请、分流和独立／联合审批 | [申请服务](../../backend/src/main/java/com/dataocean/module/access/service/impl/AccessRequestServiceImpl.java)、[React 页面](../../frontend/src/modules/access/RequestsPage.tsx)、[子项资料与经办历史](../../backend/src/main/resources/db/migration/V9__access_item_application_and_history.sql) |
| 当前访问范围、来源回看和撤销 | [范围服务](../../backend/src/main/java/com/dataocean/module/access/service/AccessScopeService.java)、[React 范围与来源](../../frontend/src/modules/access/ScopePages.tsx)、[访问表](../../backend/src/main/resources/db/migration/V5__data_access.sql)、[规则关系](../../backend/src/main/resources/db/migration/V7__access_rule_references.sql) |

当前验证见[授权验收](../backend/access/验收.md)。

## 模块定位专题

001 与 007 定位首批正式实现。其余专题仅说明仍保留在 `backend/DataOcean`、`python-service/dataocean` 的实现，用于后续评估，不代表已接入新版应用；原有 Vue 业务页面已被正式 React 工程替换，不再作为源码入口。后续模块的界面依据从[页面专题](../frontend/pages/README.md)进入，正式覆盖以[前端状态](../frontend/README.md#当前实现与验证边界)为准。

| 模块 |
| --- |
| [001 用户与认证](001-user.md) |
| [002 数据源与就绪检查](002-datasource.md) |
| [003 元数据采集与资产目录](003-metadata-collection.md) |
| [004 元数据治理与可信度](004-metadata-governance.md) |
| [005 元数据版本与审核](005-metadata-versioning.md) |
| [006 业务术语与知识文档](006-knowledge.md) |
| [007 人员、角色与功能权限](007-iam-s1.md) |
| [008 S1 问数与会话记忆](008-query-memory.md) |
| [009 Schema RAG 构建与检索](009-schema-rag.md) |
| [010 平台配置、通知与工作台](010-platform-operations.md) |
| [011 查询审计与数据血缘](011-audit-lineage.md) |
