# 源码定位

这里定位当前正式实现与仓库保留的其他系统代码，供开发、运行维护和复用评估。业务与设计依据分别见[产品方案](../product/README.md)、[前端开发入口](../frontend/README.md#开发前必读与完成验收)、[后端设计](../backend/README.md)和[系统架构](../architecture/README.md)，不能用源码偏差改写已确认规则。

各端 README 维护实际模块和文件职责。本页按业务串联各端入口。新增、移动、拆分或删除模块及关键文件时，同步更新所属 README 和受影响的跨端链接。

## 首批正式实现

| 范围 | 实际入口 |
| --- | --- |
| 应用入口与公共能力 | [React 应用入口](../../frontend/README.md#应用入口)、[共用组件与请求](../../frontend/README.md#共用组件与请求)、[Java 应用入口与公共基础](../../backend/README.md#应用入口与公共基础) |
| 登录、人员、部门、角色与审计 | [React 人员与权限](../../frontend/README.md#人员与权限)、[Java 人员与权限](../../backend/README.md#人员与权限)、[Java 审计](../../backend/README.md#审计)、[配置与迁移](../../backend/README.md#配置迁移与测试) |
| Python 正式 API 及公共能力 | [Python 源码导览](../../python-service/README.md#源码导览) |

以上仅说明已存在的源码位置，不代表逐接口、界面或跨端链路全部验收通过。离线原型的定位仍由[页面专题](../frontend/pages/README.md#原型定位与衔接)维护。

## 数据源接入与采集

| 范围 | 入口 |
| --- | --- |
| 连接配置、采集任务、目录、结构变化和企业业务说明 | [React 页面](../../frontend/README.md#数据源接入与采集)、[Java 业务及接口入口](../../backend/README.md#数据源接入与采集)、[表结构](../backend/datasource/表结构.md) |

真实覆盖见[数据源验收](../backend/datasource/验收.md#当前验证范围)。

## 数据访问授权

| 范围 | 入口 |
| --- | --- |
| 审批责任、输出限制、部门初始配置、申请审批及访问范围 | [React 页面](../../frontend/README.md#数据访问授权)、[Java 业务及接口入口](../../backend/README.md#数据访问授权)、[授权设计与接口](../backend/access/设计与接口.md) |

当前验证见[授权验收](../backend/access/验收.md)。

## 模块定位专题

表说明与字段治理已接入正式 [React 工作台](../../frontend/README.md#表说明与字段治理)与 [Java 治理模块](../../backend/README.md#表说明与字段治理)。Python 生成入口见[表说明和字段解释](../../python-service/README.md#表说明和字段解释)。当前实测范围见[C 批验收](../backend/governance/验收.md)。

首个知识事项已接入正式 [React 知识页面](../../frontend/README.md#知识文档与发布准备)及 [Java 知识模块](../../backend/README.md#知识文档与发布准备)。正式版本的隔离恢复与解除限制由 [KnowledgeRecoveryService](../../backend/src/main/java/com/dataocean/module/knowledge/service/KnowledgeRecoveryService.java) 编排，并沿用候选验证的执行事实与证据校验。实际范围见[D 批验收](../backend/knowledge/验收.md)。

正式问数首条链路由 [React 问数工作台](../../frontend/src/modules/query/QueryWorkbench.tsx)、[Java 问数接口](../../backend/src/main/java/com/dataocean/module/query/controller/QueryController.java)和 [Python 检索与模型入口](../../python-service/src/dataocean_ai/query/api.py)组成。Java 的请求和回看由 [QueryService](../../backend/src/main/java/com/dataocean/module/query/service/QueryService.java) 处理，后台领任务、执行和交付由 [QueryWorker](../../backend/src/main/java/com/dataocean/module/query/service/QueryWorker.java) 负责，两者通过 [QueryRunAccess](../../backend/src/main/java/com/dataocean/module/query/service/QueryRunAccess.java) 核对原发起人权限。当前覆盖见[问数首条链路验收](../backend/query/设计与验收.md)。

F 批评价、入队和分派由 [React 回答评价](../../frontend/src/modules/query/AnswerRating.tsx)、[问题队列](../../frontend/src/modules/feedback/FeedbackQueue.tsx)、[事项详情](../../frontend/src/modules/feedback/FeedbackIssuePage.tsx)和 [Java 反馈接口](../../backend/src/main/java/com/dataocean/module/feedback/controller/FeedbackController.java)组成。问数的实际引用由 [QueryUsage](../../backend/src/main/java/com/dataocean/module/query/service/QueryUsage.java)生成，表结构见 [V38 入队迁移](../../backend/src/main/resources/db/migration/V38__feedback_rating_intake.sql)和 [V39 核查迁移](../../backend/src/main/resources/db/migration/V39__feedback_issue_triage.sql)。

反馈概览由 [React 反馈概览](../../frontend/src/modules/feedback-overview/FeedbackOverview.tsx)、[概览接口](../../backend/src/main/java/com/dataocean/module/feedback/controller/FeedbackOverviewController.java)及[统计 SQL](../../backend/src/main/java/com/dataocean/module/feedback/mapper/FeedbackOverviewMapper.java)组成。[V49 迁移](../../backend/src/main/resources/db/migration/V49__feedback_overview_history.sql)记录状态覆盖起点，详见[反馈运营统计](../backend/feedback/运营统计.md)。

接入概览由 [React 接入概览](../../frontend/src/modules/datasource/SourceOverview.tsx)、[概览接口](../../backend/src/main/java/com/dataocean/module/datasource/controller/SourceOverviewController.java)和[统计 SQL](../../backend/src/main/java/com/dataocean/module/datasource/mapper/SourceOverviewMapper.java)组成。图表共用 [React 看板组件](../../frontend/src/modules/overview/OverviewPanel.tsx)，分组共用 [统计区间](../../backend/src/main/java/com/dataocean/common/overview/OverviewPeriod.java)，口径见[接入运营统计](../backend/datasource/运营统计.md)。

治理概览由 [React 治理概览](../../frontend/src/modules/governance/GovernanceOverview.tsx)、[概览接口](../../backend/src/main/java/com/dataocean/module/governance/controller/GovernanceOverviewController.java)、[确认 SQL](../../backend/src/main/java/com/dataocean/module/governance/mapper/GovernanceOverviewMapper.java)与[历史进度 SQL](../../backend/src/main/java/com/dataocean/module/governance/mapper/GovernanceProgressMapper.java)组成。见[治理运营统计](../backend/governance/运营统计.md)。

知识概览由 [React 知识概览](../../frontend/src/modules/knowledge/KnowledgeOverview.tsx)、[概览接口](../../backend/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeOverviewController.java)、[期间活动 SQL](../../backend/src/main/java/com/dataocean/module/knowledge/mapper/KnowledgeOverviewMapper.java)与[期末可用性](../../backend/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeOverviewHistory.java)组成。见[知识运营统计](../backend/knowledge/运营统计.md)。

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
