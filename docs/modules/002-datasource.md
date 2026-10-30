# 002 数据源与就绪检查

> 状态：现行模块说明；整理日期：2026-10-24。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 配套功能定位

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 数据源增删改、启停与保存后连接测试 | [DatasourceServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceServiceImpl.java)：`createDatasource`、`updateDatasource`、`deleteDatasource`、`updateStatus`、`testSavedConnection` | 管理范围、连接变更与生命周期。 |
| 连接测试实现 | [DatasourceConnectionServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceConnectionServiceImpl.java)：`testConnection` | 真实业务源必须使用只读账号。 |
| 密码加解密 | [DatasourceSecretServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceSecretServiceImpl.java)：`encrypt`、`decrypt` | AES-256 与秘密信息边界。 |

DataSourcesView/DataSourceDetailView → datasource API → DatasourceAdminController → 服务。秘密信息测试不验证真实连通性；连接测试成功也不证明快照、知识、build 和权限已就绪。

验证入口：[DatasourceSecretServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/datasource/service/impl/DatasourceSecretServiceImplTest.java)（加密实现）。本轮仅核对入口，未执行这些业务测试。

## 修改定位

先按修改意图定位符号，再阅读下方行为约束。源码定位核对日期：2026-10-26；不代表运行验收。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 后台、业务与批量就绪判断 | [DatasourceReadinessServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceReadinessServiceImpl.java)：`getAdminReadiness`、`getCurrentUserReadiness`、`getBatchAdminReadiness` | 管理范围、可查范围和生命周期分别判断。 |

后台 DatasourceAdminController 和业务 IamS1UserResourceController 是不同入口。修改阻断条件时同时检查快照发布门禁与活动 build。

验证入口：[DatasourceReadinessServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/datasource/service/impl/DatasourceReadinessServiceImplTest.java)（就绪条件）。本次只核对测试入口，修改实现后按[开发指南](../development/本地开发与验证.md)运行相关测试，再按影响范围补充接口或页面验证。

## 概览与边界

负责单个 MySQL 数据源的连接信息、启停、连接测试、健康检查和准备条件。密码以 AES-256 加密保存。每次问数选择一个源，同库多表；不做跨库 SQL。

管理负责源、业务查询授权、readiness 是三种不同判断。管理员能维护数据源，不等于能查询业务数据；“连接成功”也不等于“已可问数”。

## 主流程

建议准备路径：注册/测试连接 → 采集快照 → 质量处理与审核发布 → 文档审核发布与显式 RAG build；问数另按当前用户授权与服务端就绪检查执行。这是任务分工，不是前端新增的一组串行门禁。

当前实现的 `askable` 只要求连接、已发布快照、无当前阻断问题和有效授权，不包含 `knowledgeReady`。`knowledgeReady` 实际表示存在活动 RAG build；无活动索引或索引来源落后可以与可问数并存。后台 `permissionReady` 表示已有有效数据授权配置，不代表查看该页的管理员本人拥有查询权；普通问数使用当前用户入口单独核验。

readiness 汇总连接、快照、阻断问题、知识/活动构建及权限相关条件，前端直接展示后端阻断原因。不在前端自算就绪状态，不为了截图改变授权。阻断问题口径与发布门禁共用 `OPEN/CONFIRMED/REOPENED`，门禁的审核/发布判定由[版本模块](005-metadata-versioning.md)维护。

## 数据源页面 V2

列表先解释连接、采集、治理、发布、索引、问数的分工，再展示各源真实阶段、采集/正式版本与活动索引状态。详情将当前首要原因、协作角色和有权下一步放在前面，准备路径逐项解释事实；最近知识文档不冒充整组发布，治理布尔值不冒充字段释义已全部审核。映射与边界见[第二阶段功能映射](../development/前端第二阶段-数据源功能映射.md)。

- [DataSourcesView.vue](../../frontend/src/views/admin/datasource/DataSourcesView.vue)：服务端筛选/分页、连接表单、启停删除；旧列表/测试响应按请求代次和账号接纳，编辑深链可精确读取非首屏目标。连接测试只绑定当时配置；未改连接的编辑允许沿用密码，新建后刷新能力决定是否能进入详情，不自动授予负责源或数据权。
- [DataSourceDetailView.vue](../../frontend/src/views/admin/datasource/DataSourceDetailView.vue)：按路由源、账号与能力隔离请求和操作；只按本源独立能力读取快照、采集、知识与治理问题，缺权与请求失败分别显示。首要动作保留后端原因顺序，无采集/已有未发布快照依据 readiness 事实分流。
- [DatasourcePreparation.vue](../../frontend/src/components/admin/DatasourcePreparation.vue)、[datasourcePreparation.ts](../../frontend/src/utils/datasourcePreparation.ts)：六项事实和有权工作区链接，不负责重新裁定 askable。

连接窗口保留分区校验、测试与保存；后台外层滚动锁定，长表单正文内滚动。确认框仅关闭当前实例拥有的框，账号/页面改变后不继续旧写入；创建后的能力刷新也不能覆盖新草稿或筛选意图。

实际前端与合成 API 浏览器成绩见[第二阶段验收](../acceptance/2026-10-29-后台与数据源V2.md)，不能由此推定真实数据库、模型或新 UI 真实后端联调通过。

持久化对象位于 `entity.po`，列表查询使用 `entity.query.DatasourceQuery` 继承公共 `PageQuery`。分页、别名及排序字段白名单见[架构说明](../技术栈与模块职责.md#java-对象目录与分页查询)。可见数据源范围仍在 SQL 分页前过滤。

## 代码与关键接口

| 代码 | 入口或用途 |
| --- | --- |
| [DatasourceAdminController](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/controller/DatasourceAdminController.java) | `/api/admin/datasources`下 CRUD、simple、连接测试、readiness 与批量 readiness |
| [DatasourceReadinessServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceReadinessServiceImpl.java) | 生命周期准备条件 |
| [IamS1UserResourceController](../../backend/DataOcean/src/main/java/com/dataocean/module/permission/s1/controller/IamS1UserResourceController.java) | `/api/iam-s1/query-resources/datasources`及 `/{datasourceId}/readiness`等业务问数入口 |
| [datasource/service](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service) | 连接、秘密信息和管理服务 |
| [DatasourceHealthCheckScheduler](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/scheduler/DatasourceHealthCheckScheduler.java) | 定时健康检查 |
| [数据源 API](../../frontend/src/api/admin/datasource.ts)、[S1 API](../../frontend/src/api/iamS1.ts) | 管理端与业务端分别接线 |

后台页面为 [DataSourcesView](../../frontend/src/views/admin/datasource/DataSourcesView.vue)和 [DataSourceDetailView](../../frontend/src/views/admin/datasource/DataSourceDetailView.vue)。旧 `DatasourceUserController/DatasourceAccessService`与 `datasource_access`不再是当前实现；旧 `/api/datasources`说明由上述 S1 query-resources 接口替代。

列表和详情以当前 S1 摘要控制操作：查看沿用 `datasource:view`，测试连接、编辑、启停和删除要求目标源上的 `datasource:manage`；其他源的维护能力不会开放当前源的按钮，事件处理器也复核同一能力。新建沿用服务端的全局功能判定，不从某个负责源推导。详情的采集入口独立要求 `metadata:collect:run`，数据源维护权不代替采集权。页面控制只避免无权操作，服务端仍执行最终授权。

## 异步与变更注意

定时批量健康检查由 DatasourceHealthCheckScheduler 提交给专用执行器，不占用公共 ForkJoinPool；不能用这一点推断所有手动连接测试接口都会异步返回。执行器饱和时的调用方回退边界见[技术栈与模块职责](../技术栈与模块职责.md#54-执行约束)。数据源变更与 Python 连接池通知沿用现有客户端；不能把 SSE 或健康检查套成盲目重试。授权变更走 IAM 修订与当前权限复查，不新增旧授权 CRUD。

修改就绪判断时验证缺失条件、无权、部分失败和正常分支。真实环境使用[本机开发规则](../development/本地开发与验证.md)，不自动重建或重置基础设施。
