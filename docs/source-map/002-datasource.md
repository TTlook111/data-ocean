# 002 数据源与就绪检查

旧版登记单个 MySQL 业务数据源，管理连接、启停和健康记录。问数任务选定一个源，在该库内访问有权的表和字段。

## 登记与连接

- `/api/admin/datasources` 提供搜索、分页、创建、编辑、启停、删除和连接测试。维护已有数据源时校验本源权限，删除前要求数据源已停用。[管理接口](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/controller/DatasourceAdminController.java) · [数据源服务](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceServiceImpl.java)
- 登记信息、凭据和健康记录分别映射到 `datasource`、`datasource_secret`、`datasource_health_check`。连接密码以 AES-256 加密保存，手工测试和定时检查通过连接服务访问目标库。[持久化对象](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/entity/po) · [凭据处理](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceSecretServiceImpl.java) · [连接测试](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceConnectionServiceImpl.java) · [健康检查调度](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/scheduler/DatasourceHealthCheckScheduler.java)

## 问数就绪判断

服务端综合连接、正式快照、阻断问题和授权四项条件计算 `askable`。后台检查该源是否已有有效查询授权，业务端检查当前用户能否查询。活动 RAG 构建单独记为 `knowledgeReady`，不参与 `askable` 合取。准备状态、阻断原因、正式快照与活动索引分别返回。[就绪判断](../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceReadinessServiceImpl.java) · [业务用户入口](../../backend/DataOcean/src/main/java/com/dataocean/module/permission/s1/controller/IamS1UserResourceController.java)

后台列表管理连接和状态，详情页按权限读取选中数据源的采集、正式快照、治理和索引信息。[数据源列表](../../frontend/src/views/admin/datasource/DataSourcesView.vue) · [数据源详情](../../frontend/src/views/admin/datasource/DataSourceDetailView.vue)
