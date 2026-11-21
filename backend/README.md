# DataOcean Java 后端

本工程提供身份与权限、审计、数据源接入与采集、数据访问授权的 Java 服务。业务规则见[产品模块](../docs/product/README.md#按模块读取)，接口和数据设计见[后端设计入口](../docs/backend/详细设计与验收.md)，版本、启动和复验命令见[运行与验收](../docs/backend/运行与验收.md)。

## 应用入口与公共基础

| 文件或目录 | 职责 |
| --- | --- |
| [DataOceanApplication.java](src/main/java/com/dataocean/DataOceanApplication.java) | 启动 Spring Boot、启用调度并扫描业务 Mapper |
| [common](src/main/java/com/dataocean/common/) | 统一返回、分页、标识与时间转换等共用类型和工具 |
| [GlobalExceptionHandler.java](src/main/java/com/dataocean/common/exception/GlobalExceptionHandler.java) | 将业务异常、参数校验失败等转换为统一接口错误 |
| [UserContext.java](src/main/java/com/dataocean/common/security/UserContext.java) | 从安全上下文获取当前请求的主体身份 |
| [infrastructure](src/main/java/com/dataocean/infrastructure/) | 装配平台配置、JSON、密钥及持久化和 HTTP 安全基础设施 |
| [MybatisConfig.java](src/main/java/com/dataocean/infrastructure/persistence/MybatisConfig.java) | 配置 MyBatis-Plus 分页 |
| [HealthController.java](src/main/java/com/dataocean/infrastructure/security/HealthController.java) | 提供 Java 服务健康检查入口 |

业务包共用同一套文件组织。`controller` 接收 HTTP 请求，`service` 定义业务能力，`service/impl` 执行规则和事务，`mapper` 处理数据库读写。`entity/dto` 放命令输入，`entity/query` 放查询条件，`entity/vo` 放接口返回，`entity/po` 对应持久化记录。对象的校验、字段含义和暴露范围按[Java 编码细则](../docs/backend/编码规范.md)与各模块接口核对。

## 人员与权限

[iam](src/main/java/com/dataocean/module/iam/) 管理登录与会话、本人账号、人员和部门、角色、功能权限及受保护操作。业务动作与字段见[人员与权限接口](../docs/backend/iam/接口.md)。

| 文件 | 职责 |
| --- | --- |
| [AuthController.java](src/main/java/com/dataocean/module/iam/controller/AuthController.java)、[AuthServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/AuthServiceImpl.java) | 登录、退出、首次改密和会话管理 |
| [AccountController.java](src/main/java/com/dataocean/module/iam/controller/AccountController.java) | 本人资料和密码修改 |
| [UserController.java](src/main/java/com/dataocean/module/iam/controller/UserController.java)、[DepartmentController.java](src/main/java/com/dataocean/module/iam/controller/DepartmentController.java) | 人员管理、部门树和部门成员操作 |
| [RoleController.java](src/main/java/com/dataocean/module/iam/controller/RoleController.java)、[RoleServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/RoleServiceImpl.java) | 角色配置、状态变更和历史恢复 |
| [PermissionController.java](src/main/java/com/dataocean/module/iam/controller/PermissionController.java)、[IdentityOptionController.java](src/main/java/com/dataocean/module/iam/controller/IdentityOptionController.java) | 提供角色配置使用的功能目录，以及按当前操作筛选的部门、角色和人员选项 |
| [PermissionServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/PermissionServiceImpl.java) | 计算当前页面权、操作权及可授予上限 |
| [AssignmentServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/AssignmentServiceImpl.java)、[ProtectedIdentityController.java](src/main/java/com/dataocean/module/iam/controller/ProtectedIdentityController.java) | 个人角色分配、受保护身份管理及恢复入口 |
| [IamSecurityConfiguration.java](src/main/java/com/dataocean/module/iam/config/IamSecurityConfiguration.java)、[CurrentIdentityFilter.java](src/main/java/com/dataocean/module/iam/security/CurrentIdentityFilter.java) | 配置请求安全链和 CSRF，并复核当前会话身份 |
| [RequirePermission.java](src/main/java/com/dataocean/module/iam/security/RequirePermission.java)、[MethodPermissionConfiguration.java](src/main/java/com/dataocean/module/iam/config/MethodPermissionConfiguration.java) | 声明并执行接口方法的功能权限校验 |
| [ReviewServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/ReviewServiceImpl.java)、[MutationServiceImpl.java](src/main/java/com/dataocean/module/iam/service/impl/MutationServiceImpl.java) | 生成和校验操作核对凭据，处理幂等命令及事务提交 |
| [CommandController.java](src/main/java/com/dataocean/module/iam/controller/CommandController.java)、[OutboxDispatcher.java](src/main/java/com/dataocean/module/iam/service/OutboxDispatcher.java) | 查询命令处理结果，将已提交的 Outbox 事件派发到 Redis |

## 审计

[audit](src/main/java/com/dataocean/module/audit/) 保存和查询业务操作审计。接口见[审计与命令核实](../docs/backend/iam/接口.md#审计与命令核实)。

| 文件 | 职责 |
| --- | --- |
| [AuditController.java](src/main/java/com/dataocean/module/audit/controller/AuditController.java) | 接收审计记录查询和详情请求 |
| [AuditServiceImpl.java](src/main/java/com/dataocean/module/audit/service/impl/AuditServiceImpl.java) | 写入成功、拒绝和失败审计，提供查询与详情数据 |
| [MutationFailureFilter.java](src/main/java/com/dataocean/module/audit/MutationFailureFilter.java) | 在安全上下文建立后捕获业务拒绝或异常，支持回滚后的独立失败审计 |

## 数据源接入与采集

[datasource](src/main/java/com/dataocean/module/datasource/) 负责只读连接、采集任务、元数据目录、结构变化核实和企业业务说明。运行状态及跨模块规则见[采集设计](../docs/backend/datasource/设计.md)与[接口](../docs/backend/datasource/接口.md)。

| 文件 | 职责 |
| --- | --- |
| [DatasourceController.java](src/main/java/com/dataocean/module/datasource/controller/DatasourceController.java)、[DatasourceServiceImpl.java](src/main/java/com/dataocean/module/datasource/service/impl/DatasourceServiceImpl.java) | 数据源查询、连接分项测试、配置核对与建档 |
| [SourceManagement.java](src/main/java/com/dataocean/module/datasource/service/impl/SourceManagement.java)、[CredentialMaintenance.java](src/main/java/com/dataocean/module/datasource/service/impl/CredentialMaintenance.java) | 管理数据源配置及生命周期，处理在途采集期间的凭据替换 |
| [CollectionController.java](src/main/java/com/dataocean/module/datasource/controller/CollectionController.java)、[CollectionServiceImpl.java](src/main/java/com/dataocean/module/datasource/service/impl/CollectionServiceImpl.java) | 提供采集发起、取消、状态回看及元数据目录读取 |
| [DatasourceScheduler.java](src/main/java/com/dataocean/module/datasource/service/DatasourceScheduler.java)、[CollectionRunner.java](src/main/java/com/dataocean/module/datasource/service/CollectionRunner.java) | 按配置接收定时采集并执行持久化任务 |
| [CatalogPublication.java](src/main/java/com/dataocean/module/datasource/service/CatalogPublication.java)、[CollectionEventStream.java](src/main/java/com/dataocean/module/datasource/service/impl/CollectionEventStream.java) | 比较和发布目录快照，向订阅端提供采集状态事件 |
| [StructureChangeController.java](src/main/java/com/dataocean/module/datasource/controller/StructureChangeController.java)、[StructureReviewService.java](src/main/java/com/dataocean/module/datasource/service/impl/StructureReviewService.java) | 查询结构变化及证据，提交来源事实的核实结论 |
| [BusinessNoteController.java](src/main/java/com/dataocean/module/datasource/controller/BusinessNoteController.java)、[BusinessNoteServiceImpl.java](src/main/java/com/dataocean/module/datasource/service/impl/BusinessNoteServiceImpl.java) | 管理企业业务说明、修订、附件原件和引用检查 |
| [SourceJdbc.java](src/main/java/com/dataocean/module/datasource/source/SourceJdbc.java)、[SourceProbe.java](src/main/java/com/dataocean/module/datasource/source/SourceProbe.java) | 执行 MySQL／PostgreSQL 源库元数据读取和只读能力验证 |

## 数据访问授权

[access](src/main/java/com/dataocean/module/access/) 管理审批责任、输出限制、部门初始配置、访问申请及生效授权。权限与状态规则见[授权设计与接口](../docs/backend/access/设计与接口.md)。

| 文件 | 职责 |
| --- | --- |
| [AccessController.java](src/main/java/com/dataocean/module/access/controller/AccessController.java) | 数据访问配置、申请、审批、授权管理及访问范围的 HTTP 入口 |
| [AccessConfigurationServiceImpl.java](src/main/java/com/dataocean/module/access/service/impl/AccessConfigurationServiceImpl.java) | 维护审批责任、输出限制和直属部门初始配置 |
| [AccessRequestServiceImpl.java](src/main/java/com/dataocean/module/access/service/impl/AccessRequestServiceImpl.java) | 处理申请、分流、独立或联合审批及批准后的授权生效 |
| [AccessScopeService.java](src/main/java/com/dataocean/module/access/service/AccessScopeService.java)、[AccessScopeServiceImpl.java](src/main/java/com/dataocean/module/access/service/impl/AccessScopeServiceImpl.java) | 定义并计算当前访问范围，查询授权来源与历史并处理结束授权 |

## 表说明与字段治理

治理工作台由 `module/governance` 承接，B 批成功目录及资料读取通过 `GovernanceCatalog` 和 `GovernanceNotes` 提供。来源事务同步投影治理影响，Flyway V10 建立条目、修订、证据与复核，V11 建立表说明生成任务和实际调用记录。接口及验收见[C 批详细设计](../docs/backend/governance/设计.md)。

表说明生成通过 `dataocean.governance-ai.enabled` 独立开启。配置 Python 服务地址 `python-url`、Java 签名文件 `java-private-key-file`、Java 和 Python 的可信公钥文件 `java-public-key-file`、`python-public-key-file`，以及已核准发送结构和资料的 `model-source-ids`。实际文件和端点按机器配置保存。治理草稿、确认和来源复核可独立运行。

## 知识文档与发布验证

[module/knowledge](src/main/java/com/dataocean/module/knowledge) 维护已确认治理引用上的知识文档、标准题和发布任务。V24 建立文档修订、引用与确认，V25 建立题目修订和审核，V26 持久化候选，V27 追加索引构建和固定片段，V28 持久化准备任务，V29 保存范围调整和候选历史归属，V30 持久化逐题运行、实际证据和人工裁定，V31 建立权威生效绑定和直接上一版。构建、验证和发布读回复用内部 AI 服务身份，`dataocean.knowledge-index.model-source-ids` 限定已准入的确认知识来源，`collection-prefix` 区分 Milvus 部署，`validation-allowed-rule-revisions` 指定可兼容验证结果的规则修订。治理交接走 [GovernanceKnowledgeService](src/main/java/com/dataocean/module/governance/service/GovernanceKnowledgeService.java)。接口和当前覆盖见[首个事项](../docs/backend/knowledge/接口.md)。

## 配置、迁移与测试

| 文件或目录 | 职责 |
| --- | --- |
| [pom.xml](pom.xml)、[Maven Wrapper](mvnw.cmd) | 声明正式构建依赖和插件，通过 Wrapper 使用工程指定的 Maven |
| [application.yml](src/main/resources/application.yml)、[本机配置示例](src/main/resources/application-local.example.yml) | 共享运行配置与本机覆盖模板 |
| [iam-seeds.json](src/main/resources/iam-seeds.json)、[IdentityBootstrap.java](src/main/java/com/dataocean/module/iam/config/IdentityBootstrap.java) | 提供 IAM 初始化种子及启动时的初始化逻辑 |
| [V1](src/main/resources/db/migration/V1__identity_baseline.sql)、[V2](src/main/resources/db/migration/V2__permission_page_names.sql)、[V4](src/main/resources/db/migration/V4__audit_record_time_default.sql) | 建立身份权限基线，调整页面名称及审计时间默认值 |
| [V3](src/main/resources/db/migration/V3__datasource_collection.sql)、[V6](src/main/resources/db/migration/V6__business_notes.sql)、[V8](src/main/resources/db/migration/V8__business_note_file_limits.sql) | 建立数据源、采集目录和企业业务说明相关表，调整原件容量 |
| [V5](src/main/resources/db/migration/V5__data_access.sql)、[V7](src/main/resources/db/migration/V7__access_rule_references.sql)、[V9](src/main/resources/db/migration/V9__access_item_application_and_history.sql) | 建立访问授权、规则引用、申请子项和经办历史的表结构 |
| [Java 测试](src/test/java/com/dataocean/) | 按源码职责覆盖公共能力、基础设施、身份权限、审计入口和源库适配 |

旧 [DataOcean 工程](DataOcean/)保留供维护和复用评估，定位入口见[源码地图](../docs/source-map/README.md#模块定位专题)。
