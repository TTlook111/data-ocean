# 轨道 B-B0 权限清单与决策冻结

> 文档版本：B0-1.1；盘点与评审日期：2026-09-16；基线提交：`8c13aa9`；分支：`codex/permission-system-guide`
>
> 状态：B0 文档已完成并评审通过。本文件只冻结开发基线，不表示 B1、数据库升级、权限代码或真实运行验收已经完成。
>
> 主设计：[`docs/development/guides/DataOcean-完整权限体系设计.md`](guides/DataOcean-完整权限体系设计.md) 简明版 1.4；执行协议：`IAM-SIMPLE-1`。

## 0. B0 范围与冻结结论

本次只做源码与文档盘点、目标功能消费矩阵、存储隔离决策、Java/Python 契约、首个新系统管理员流程、B6 删除/保留清单和准入验收矩阵。B0 编写与评审期间未修改 Java、Python、Vue、SQL migration 或测试代码，未启动服务、Docker 或数据库。

### 0.1 必须遵守的禁止规则

以下规则是 IAM-SIMPLE-1 的不可变边界：

- 旧权限体系不迁移、不映射、不回填、不双读双写、不做影子对账、不作为兼容兜底。
- 新权限计算不得读取旧角色权限关系、旧数据授权/策略、旧 JWT authority、旧权限缓存或旧 Resolver。
- 即使新旧权限码字符串同名，也不能继承旧角色或旧授权关系。
- IAM-SIMPLE-1 建立、独立验收并正式切换后，B6 删除全部旧权限专属配置、关系、算法、接口、缓存和前端入口。
- B5 失败只能执行整套环境/入口级回退；不能在单个请求被新规则拒绝时调用旧 Resolver。
- Java 是权限事实、生命周期、持久化和最终脱敏的唯一负责人；Python 只消费请求级权限快照并执行 RAG、SQL AST 和沙箱边界；Vue 不是安全边界。
- 新缓存只能使用 Redis。缓存故障必须回源数据库或直接拒绝，不能扩大资源范围。
- 不创建数据库外键；关联完整性由 Java 服务层校验。

### 0.2 已冻结的方案

| 决策 | 冻结结果 |
|---|---|
| 新权限存储隔离 | **独立的新权限表/新实体/新 Mapper/新 Resolver**；不在 `sys_permission`、`sys_user_role`、`sys_role_permission`、`datasource_access` 或 `datasource_access_policy` 中插入新记录。 |
| 新协议 | 所有新权限请求和权限快照必须带 `protocolVersion = IAM-SIMPLE-1`；缺失、空值、未知值一律拒绝。 |
| 事实关系 | 新角色管功能；用户绑定主部门与角色；同一个用户-角色绑定保存后台负责源；部门/角色/用户授权保存明确的数据源、表、字段、记录条件、有效期和关联来源。 |
| 计算模型 | 每个动作独立计算；`DENY > ALLOW > UNSET`。查询必须同时具备 `query:use`、覆盖所选数据源及全部引用表列的有效 S1 ALLOW grant，并保留对应记录条件；不再另设一层旧式“数据源查询开关”。后台操作要求“同一角色同时拥有功能和目标源负责范围”。 |
| 禁止边界 | DENY 只支持数据源、表或字段级，不支持记录级 DENY；结构化记录条件只能附着在 ALLOW grant 上，不引入另一套记录优先级。 |
| 字段保护 | 独立于数据授权；`HIDDEN` 不进入 Schema/RAG/SQL，`MASKED` 只能以保护值返回；Java 对完整响应做最终检查和脱敏。 |
| 缓存 | 新权限快照使用 Redis，键包含用户、数据源、权限修订版本和协议版本；读取失败回源数据库，写入失败不阻断主链路。 |
| 首个管理员 | 由部署负责人显式选择已保留且启用的账号，一次性建立新的系统管理员绑定，不读取任何旧管理员事实。 |
| B1 门槛 | 本文完成评审、阻塞项被逐项关闭、所有 54 个目标码都有消费结论、B6 清单和保留清单经评审后，才可以进入 B1。 |

## 1. 盘点口径与源码证据索引

### 1.1 数量口径

| 对象 | 盘点结果 | 口径 |
|---|---:|---|
| Java Controller 类 | 37 | `backend/DataOcean/src/main/java/com/dataocean/**/**Controller.java` |
| Java 方法级 API 注解 | 211 | 只数 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping` 和带 HTTP method 的 `@RequestMapping`，不数类级 `@RequestMapping`。 |
| Java 用户/管理 API 注解 | 208 | 211 减去 `InternalMetadataController`、`PromptInternalController`、`InternalAiConfigController` 的 3 个内部方法级 API。 |
| 公开路径变体 | 226 | 208 个公开 API 加上 `RoleController`、`DepartmentController`、`PermissionController` 三个双前缀 Controller 产生的 18 个额外路径变体。 |
| Python 路由声明 | 27 | `main.py` 注册的 Agent、RAG、SQL、知识、Prompt、图表、配置和健康路由；其中 25 个模块/配置路由由统一内部令牌依赖保护，健康路由单独注册；不等同 Java API 数。 |
| 后台正式路由声明 | 26 | `frontend/src/router/index.ts` 的 `/admin` 子路由，含 20 个工作区入口和 6 个详情/新建/差异对象页。 |
| 正式工作区 | 20 | 7 个一级业务域；工作台 1 个加固定工作区 19 个。 |
| 功能 Tab | 49 | 后台 `el-tab-pane` 的运行时功能项 43 个，AI 配置自定义 Tab 2 个，问数结果 Tab 4 个。动态审批 Tab 按运行时 3 个计。 |
| 按钮标签/组件声明 | 277 | 后台视图 237、后台 Shell 6、问数视图 34；包含刷新、取消、关闭、重试等通用控件。目标矩阵只标记与权限动作有关的控件。 |
| 目标功能码 | 54 | 主设计第 4 节提取，脚本校验唯一值 54、重复 0、遗漏 0。 |

“现有消费”只表示源码中确有页面、按钮或 API 业务动作；“目标码直接消费”还要求动作已经由该 IAM-SIMPLE-1 码强制授权。现阶段前者多数存在，后者尚未建立。

### 1.2 证据索引

| 编号 | 真实证据 |
|---|---|
| E-DESIGN | `docs/development/guides/DataOcean-完整权限体系设计.md:3-9,146-181,344-414`：版本、54 个目录、依赖、协议与替换顺序。 |
| E-NAV | `frontend/src/router/adminNavigation.ts:28-58`、`frontend/src/router/index.ts:88-252`、`frontend/src/components/admin/AdminDomainNav.vue:21-92`：7 域、19 固定工作区、26 后台路由、上下文模式和侧栏。 |
| E-GUARD | `frontend/src/router/guards.ts:4-73`、`frontend/src/stores/auth.ts:14-49`、`frontend/src/views/query/QueryDatasourceView.vue:24-42`：前端粗粒度后台门禁、旧权限数组和 `*` 判断。 |
| E-AUTH | `backend/DataOcean/src/main/java/com/dataocean/common/security/JwtTokenProvider.java:47-142`、`JwtAuthenticationFilter.java:39-108`、`UserDetailsServiceImpl.java:46-106`、`UserContext.java:35-87`：当前 JWT claim、Redis 失效检查、旧权限加载和 Spring authorities。 |
| E-ORG | `backend/DataOcean/src/main/java/com/dataocean/module/user/controller/UserController.java:40-151`、`RoleController.java:25-101`、`DepartmentController.java:24-59`、`PermissionController.java:28-121`；`frontend/src/views/admin/user/OrganizationView.vue:37-285`、`RoleList.vue:70-477`、`DepartmentTree.vue:130-242`。 |
| E-DS | `backend/DataOcean/src/main/java/com/dataocean/module/datasource/controller/DatasourceAdminController.java:45-234`、`DatasourceUserController.java:25-57`、`DatasourceAccessServiceImpl.java:108-313`、`DatasourceReadinessServiceImpl.java:90-412`；`frontend/src/views/admin/datasource/DataSourcesView.vue:316-384`、`DataSourceDetailView.vue:272-317`。 |
| E-PERM | `backend/DataOcean/src/main/java/com/dataocean/module/permission/controller/*.java`；`permission/service/impl/DatasourcePermissionServiceImpl.java:43-127`、`AccessPolicyServiceImpl.java:47-260`、`PermissionCalculatorImpl.java:45-602`、`DataMaskingServiceImpl.java:21-141`；`frontend/src/views/admin/permission/AccessControl.vue:1-844`、`AccessApprovalView.vue:1-376`。 |
| E-META | `backend/DataOcean/src/main/java/com/dataocean/module/metadata/controller/MetadataCollectionController.java:45-214`、`MetadataCatalogController.java:36-485`、`governance/controller/MetadataGovernanceController.java:27-274`、`versioning/controller/SnapshotVersionController.java:31-210`；`frontend/src/views/admin/metadata/CollectionsView.vue:1-45`、`governance/*.vue`、`releases/ReleasesView.vue:265-353`。 |
| E-KNOWLEDGE | `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java:37-337`；`frontend/src/views/admin/semantics/KnowledgeView.vue:468-541`、`KnowledgeDocView.vue:604-910`。 |
| E-GLOSSARY | `backend/DataOcean/src/main/java/com/dataocean/module/glossary/controller/GlossaryController.java:27-216`；`frontend/src/views/admin/semantics/GlossariesView.vue:1-850`。 |
| E-PROMPT | `backend/DataOcean/src/main/java/com/dataocean/module/prompt/controller/PromptTemplateController.java:25-176`；`frontend/src/views/admin/semantics/PromptsView.vue:449-568`。 |
| E-AUD | `backend/DataOcean/src/main/java/com/dataocean/module/audit/controller/AuditLogController.java:19-63`、`LineageController.java:21-67`、`LineageEdgeController.java:31-114`；`audit/service/impl/AuditLogServiceImpl.java:80-173`、`LineageServiceImpl.java:380-463`；`frontend/src/views/admin/audit/QueryAnalysisView.vue:19-29`、`DataLineage.vue`。 |
| E-SYSTEM | `backend/DataOcean/src/main/java/com/dataocean/common/health/SystemHealthController.java:25-88`、`module/dashboard/controller/DashboardController.java:17-38`、`module/system/controller/AiConfigController.java:21-145`、`OperationLogController.java:13-30`、`SyncScheduleController.java:20-59`；`frontend/src/views/admin/system/RuntimeView.vue:301-449`、`AiConfig.vue:506-796`、`OperationLogList.vue`。 |
| E-QUERY | `backend/DataOcean/src/main/java/com/dataocean/module/query/controller/QueryController.java:35-215`、`QuerySseController.java:24-80`、`query/service/impl/QueryTaskServiceImpl.java:47-145,218-390,414-476`、`query/client/impl/PythonAgentClientImpl.java:87-110,365-624`；`frontend/src/views/query/QueryResult.vue:45-149`、`composables/useQueryExport.ts:24-69`。 |
| E-PY | `python-service/dataocean/main.py:124-160`、`agent/schema.py:29-112`、`agent/router.py:44-231`、`agent/nodes/schema_retriever.py:31-112`、`agent/nodes/sql_validator.py:44-166`、`rag/schema.py:300-343`、`rag/vector_store.py:21-127`、`rag/fallback.py:43-129`、`sandbox/schema.py:16-104`、`sandbox/router.py:28-127`。 |
| E-MIG | `backend/DataOcean/src/main/resources/db/migration/V1__create_user_tables.sql:1-69`、`V2__init_roles_and_admin.sql:1-32`、`V4__create_datasource_tables.sql:1-67`、`V8__add_metadata_permission.sql`、`V13__create_knowledge_tables.sql:1-110`、`V19__add_field_tag_permission.sql`、`V25__permission_security_tables.sql:1-52`、`V27__add_role_manage_permission.sql`、`V28__add_prompt_versions_audit.sql:7-20`、`V34__add_ai_config_permissions.sql`、`V36__add_prompt_approval_workflow.sql:1-32`、`V40__permission_enhance.sql:1-41`、`V41__event_search.sql:1-43`、`V42__datasource_access_effect.sql:1-11`。 |
| E-CACHE | `backend/DataOcean/src/main/java/com/dataocean/module/permission/service/impl/PermissionCalculatorImpl.java:60-138`、`common/config/CacheConfig.java:1-45`、`query/client/impl/PythonAgentClientImpl.java:401-624`、`common/security/JwtAuthenticationFilter.java:55-68`、`module/user/service/impl/AuthServiceImpl.java:119-256`；`python-service/dataocean/infra/memory.py:29-161`、`rag/fewshot.py:43-138`。 |

## 2. 当前实现证据盘点

### 2.1 Controller API 全量清单

下表逐类覆盖当前 211 个方法级 API。`类级 guard` 表示该 Controller 的 `@PreAuthorize`；`方法覆盖` 表示单个方法上的表达式。未标注时为无 `@PreAuthorize`，但不代表匿名可用；`/internal/**` 另由内部令牌保护。

| Controller（基路径） | 方法、完整路径 | 当前 `@PreAuthorize` / 强制点 | 证据 |
|---|---|---|---|
| `SystemHealthController` `/api/admin/system` | `GET /health getSystemHealth`; `GET /sql-pools getSqlPoolDashboard`; `POST /sql-pools/{datasourceId}/reset resetSqlPool` | 类：`hasAnyAuthority('*')` | E-SYSTEM；`SystemHealthController.java:28-85` |
| `AlertController` `/api/admin/alert-rules` | `GET / listRules`; `POST / createRule`; `PUT /{id} updateRule`; `PATCH /{id}/toggle toggleRule` | 类：`audit:view,*` | E-AUD；`AlertController.java:23-60` |
| `AuditLogController` `/api/admin/audit-logs` | `GET / listAuditLogs`; `GET /{id} getDetail`; `GET /slow-queries listSlowQueries`; `GET /stats getStats` | 类：`audit:view,*`；Service 当前仅按请求条件查询 | E-AUD；`AuditLogController.java:22-63` |
| `LineageController` `/api/lineage` | `GET /table/{tableName} queryTableLineage`; `GET /column/{tableName}/{columnName} queryColumnLineage`; `GET /impact/{tableName}/{columnName} analyzeImpact`; `GET /impact/{tableName} analyzeTableImpact` | 类：`audit:view,*`；Service `requireDatasourceAccess` 使用旧数据源计算 | E-AUD；`LineageController.java:24-67` |
| `LineageEdgeController` `/api/admin/catalog/lineage` | `POST / createLineage`; `DELETE /{relationshipId} deleteLineage`; `POST /batch batchCreateLineage` | 类：`metadata:manage,*` | E-AUD；`LineageEdgeController.java:35-114` |
| `DashboardController` `/api/admin/dashboard` | `GET /stats getStats` | 类：`hasAnyAuthority('*')` | E-SYSTEM；`DashboardController.java:20-38` |
| `DatasourceAdminController` `/api/admin/datasources` | `GET /simple listSimple`; `GET / listDatasources`; `GET /{id} getDatasource`; `GET /{id}/readiness getReadiness`; `GET /readiness/batch getBatchReadiness`; `POST / createDatasource`; `PUT /{id} updateDatasource`; `DELETE /{id} deleteDatasource`; `PATCH /{id}/status updateStatus`; `POST /test-connection testConnection`; `POST /{id}/test-connection testSavedConnection`; `POST /{id}/access grantAccess`; `GET /{id}/access listAccess`; `DELETE /{id}/access/{userId} revokeAccess` | 类：`datasource:manage,*`；`/simple`、`/{id}/readiness`、`/readiness/batch` 覆盖为 `datasource:manage,metadata:manage,knowledge:manage,field-tag:manage,audit:view,security:manage,*`；Service 做存在性/数量/连接状态校验，未做新负责源校验 | E-DS；`DatasourceAdminController.java:49-234` |
| `DatasourceUserController` `/api/datasources` | `GET / listMyDatasources`; `GET /{datasourceId}/readiness getReadiness` | 无方法 guard；列表/就绪度调用旧 `DatasourceAccessService`，就绪度显式 `checkAccess` | E-DS；`DatasourceUserController.java:27-57` |
| `FeedbackReviewController` `/api/feedback-reviews` | `GET / listPendingReviews`; `POST /{feedbackId}/approve approveFeedback`; `POST /{feedbackId}/reject rejectFeedback` | 类：`field-tag:manage,*` | `FeedbackReviewController.java:28-84` |
| `FieldAdminController` `/api/admin/fields` | `GET /{fieldId}/confidence-trend getConfidenceTrend`; `POST /import-tags importTags`; `POST /auto-tag autoTag` | 类：`field-tag:manage,*` | `FieldAdminController.java:30-91` |
| `FieldConfidenceController` `/api/field-confidence` | `GET / pageConfidence`; `GET /{columnMetaId} getConfidence`; `GET /batch batchGetConfidence`; `PUT /{columnMetaId} adminSetScore`; `GET /{columnMetaId}/events getEventHistory` | GET 无 guard；PUT 方法：`field-tag:manage,*`；Service 主要做字段存在性和分数校验 | `FieldConfidenceController.java:30-106` |
| `FieldTagController` `/api/field-tags` | `POST / addTag`; `POST /batch batchAddTags`; `DELETE /{id} removeTag`; `GET /column/{columnMetaId} getTagsByColumn`; `GET /by-tag/{tagCode} getColumnsByTag`; `GET /predefined listPredefinedTags` | 类：`field-tag:manage,*` | `FieldTagController.java:33-112` |
| `UserFeedbackController` `/api/feedback` | `POST / submitFeedback` | 无 guard；Service 以当前用户和 Redis 限频校验 | `UserFeedbackController.java:23-43` |
| `GlossaryController` `/api/admin/glossary` | `GET / listGlossaries`; `POST / createGlossary`; `PUT /{id} updateGlossary`; `DELETE /{id} deleteGlossary`; `GET /{glossaryId}/terms listTerms`; `POST /{glossaryId}/terms createTerm`; `PUT /terms/{termId} updateTerm`; `DELETE /terms/{termId} deleteTerm`; `POST /terms/{termId}/submit submitForReview`; `POST /terms/{termId}/review reviewTerm`; `POST /terms/{termId}/revert revertTermToDraft`; `POST /terms/{termId}/link-column linkTermToColumn`; `DELETE /terms/{termId}/unlink-column/{entityId} unlinkTermFromColumn`; `GET /terms/{termId}/linked-columns getLinkedColumns` | 类：`metadata:manage,*`；Service 做术语状态、关系和对象存在性校验，没有按新目标码拆分 | E-GLOSSARY；`GlossaryController.java:30-216` |
| `MetadataGovernanceController` `/api/admin` | `POST /snapshots/{snapshotId}/quality-check triggerQualityCheck`; `GET /quality-rules listRules`; `PATCH /quality-rules/{ruleId} updateRuleEnabled`; `GET /snapshots/{snapshotId}/quality-issues listIssues`; `GET /quality-issues listAllIssues`; `PATCH /quality-issues/{issueId}/status handleIssue`; `PATCH /quality-issues/batch-status batchHandleIssues`; `POST /quality-issues/{issueId}/assign assignIssue`; `PATCH /snapshots/{snapshotId}/tables/{tableName}/governance-status updateTableStatus`; `PATCH /snapshots/{snapshotId}/columns/{columnId}/governance-status updateColumnStatus`; `PATCH /snapshots/{snapshotId}/tables/{tableName}/batch-governance-status batchUpdateColumnStatus`; `GET /snapshots/{snapshotId}/review-records listReviewRecords` | 类：`metadata:manage,*`；Service 做快照、状态、批量数量和状态流转校验，未做目标码/负责源校验 | E-META；`MetadataGovernanceController.java:30-274` |
| `KnowledgeDocController` `/api/admin/knowledge-docs` | `GET / listDocs`; `GET /{id} getDoc`; `POST / createDoc`; `PUT /{id} updateDoc`; `POST /{id}/submit-review submitReview`; `POST /{id}/approve approve`; `POST /{id}/reject reject`; `POST /{id}/publish publish`; `POST /{id}/generate-draft generateDraft`; `POST /generate-from-snapshot generateFromSnapshot`; `GET /{id}/review-tasks listReviewRecords`; `GET /{id}/source-snapshots listSourceSnapshots`; `GET /{id}/versions listVersions`; `GET /{id}/versions/{versionNo} getVersion`; `GET /{id}/versions/diff diffVersions`; `POST /{id}/rollback rollback`; `GET /{id}/vector-tasks listVectorTasks`; `POST /{id}/preview-chunks previewChunks` | 类：`knowledge:manage,*`；Service 做文档状态、版本和发布任务校验，未拆分查看/维护/审核/发布 | E-KNOWLEDGE；`KnowledgeDocController.java:40-337` |
| `InternalMetadataController` `/internal/metadata` | `GET /entities/{entityId}/relationships getEntityRelationships` | 无 `@PreAuthorize`；方法 `requireInternal` 检查 `X-Internal-Token` | E-PY；`InternalMetadataController.java:27-66` |
| `MetadataCatalogController` `/api/admin/catalog` | `GET /search search`; `GET /entities/{entityId} getEntityDetail`; `GET /entities/{entityId}/lineage getLineage`; `GET /entities/{columnId}/column-lineage getColumnLineage`; `GET /entities/{entityId}/downstream getDownstream`; `GET /entities getEntitiesByDatasource`; `POST /entities/{entityId}/confirm-tag confirmTag`; `DELETE /entities/{entityId}/unconfirm-tag/{tagFqn} unconfirmTag`; `GET /entities/{entityId}/tags getEntityTags`; `GET /mask-candidates getMaskCandidates`; `POST /mask-candidates/{entityId}/confirm confirmMaskCandidate`; `POST /mask-candidates/{entityId}/reject rejectMaskCandidate` | 类：`metadata:manage,*`；Service 做数据源/实体/标签校验；字段保护没有独立 Controller | E-META；`MetadataCatalogController.java:39-485` |
| `MetadataCollectionController` `/api/admin/metadata` | `POST /sync triggerSync`; `GET /sync-tasks listSyncTasks`; `GET /snapshots listSnapshots`; `GET /snapshots/{id} getSnapshotDetail`; `GET /snapshots/{id}/tables listSnapshotTables`; `GET /snapshots/{id}/tables/{tableName}/columns listSnapshotTableColumns`; `GET /snapshots/diff diffSnapshots`; `POST /snapshots/diff/record recordSnapshotDiff` | 类：`metadata:manage,*`；Service 做数据源/快照存在性和分页校验 | E-META；`MetadataCollectionController.java:48-214` |
| `AccessApprovalController` `/api/admin/access-approvals` | `POST / submitRequest`; `POST /{requestId}/review reviewRequest`; `GET / listRequests` | submit/list 无 guard；review：`security:manage,*`；list 用 `UserContext.currentPermissions()` 决定全量或本人；Service 审批时复查治理，但不复查审批人负责源且未禁止本人审批 | E-PERM；`AccessApprovalController.java:22-97` |
| `AccessPolicyController` `/api/admin/access-policies` | `POST / create`; `POST /batch batchCreate`; `PUT /{id} update`; `DELETE /{id} delete`; `GET / list` | 类：`security:manage,*`；Service 校验主体、数据源、发布快照表列和行条件，未校验操作人负责源/本人修改边界 | E-PERM；`AccessPolicyController.java:26-87` |
| `DatasourcePermissionController` `/api/admin/datasource-access` | `POST / grant`; `PUT /{id} update`; `DELETE /{id} revoke`; `GET / list`; `GET /decision decision` | 类：`security:manage,*`；Service 校验主体/数据源/重复记录，使用旧 `DatasourceAccess` 事实 | E-PERM；`DatasourcePermissionController.java:27-91` |
| `PromptInternalController` `/internal/prompts` | `GET /{code} getActiveContent` | 无 `@PreAuthorize`；方法检查 `X-Internal-Token`，生产 profile 禁止默认 token | E-PROMPT；`PromptInternalController.java:25-78` |
| `PromptTemplateController` `/api/admin/prompt-templates` | `GET / list`; `GET /effectiveness effectiveness`; `GET /{code} get`; `PUT /{code} update`; `POST /{code}/submit submitForReview`; `POST /{code}/approve approve`; `POST /{code}/reject reject`; `PATCH /{code}/enabled setEnabled`; `GET /{code}/versions versions`; `POST /{code}/rollback rollback` | 类：`prompt:manage,prompt:approve,*`；update/submit/rollback 覆盖为 `prompt:manage,*`；approve/reject 为 `prompt:approve,*`；其余查看类表达式把 manage/approve 混在一起 | E-PROMPT；`PromptTemplateController.java:28-176` |
| `QueryController` `/api/query` | `POST /ask ask`; `GET /tasks/{taskId} getTask`; `POST /tasks/{taskId}/cancel cancelTask`; `POST /tasks/{taskId}/feedback submitFeedback`; `GET /conversations/{conversationId}/messages listMessages`; `GET /conversations listConversations`; `DELETE /conversations/{conversationId} deleteConversation`; `GET /history history` | 无 `@PreAuthorize`；`ask` 检查当前用户、旧数据源授权、readiness、已发布快照；任务/会话 Service 检查本人归属；反馈请求体仍是 `Map` | E-QUERY；`QueryController.java:39-215` |
| `QuerySseController` `/api/query` | `GET /tasks/{taskId}/stream stream` | 无 `@PreAuthorize`；只取当前登录用户 ID 写日志，未用任务归属校验就注册 emitter | E-QUERY；`QuerySseController.java:44-80` |
| `AiConfigController` `/api/admin/system/ai-config` | `GET / getConfig`; `PUT / updateConfig`; `GET /providers listProviders`; `POST /providers createProvider`; `PUT /providers/{id} updateProvider`; `DELETE /providers/{id} deleteProvider`; `POST /providers/{id}/test testProvider`; `POST /providers/{id}/sync-models syncModels`; `POST /detect-dimension detectDimension` | GET 使用 `hasAnyAuthority('*', 'system:ai-config:view', 'system:ai-config:manage')`；写操作使用 `hasAnyAuthority('*', 'system:ai-config:manage')` | E-SYSTEM；`AiConfigController.java:24-145` |
| `InternalAiConfigController` `/internal/ai-config` | `GET / getRawConfig` | 无 `@PreAuthorize`；方法检查 `X-Internal-Token` | E-SYSTEM；`InternalAiConfigController.java:20-53` |
| `NotificationController` `/api/notifications` | `GET / list`; `PATCH /{id}/read markAsRead`; `PATCH /batch-read markBatchAsRead`; `GET /unread-count unreadCount` | 无 guard；Service 以当前用户过滤通知，批量上限 100 | `NotificationController.java:18-76` |
| `OperationLogController` `/api/admin/operation-logs` | `GET / list` | 类：`audit:view,*`；Service 支持多条件，但没有目标码和负责源过滤 | E-SYSTEM；`OperationLogController.java:16-30` |
| `SyncScheduleController` `/api/admin/system` | `GET /sync-schedule getSchedule`; `PUT /sync-schedule updateSchedule` | 类：`metadata:manage,*` | E-SYSTEM；`SyncScheduleController.java:23-59` |
| `AuthController` `/api/auth` | `GET /captcha captcha`; `POST /login login`; `POST /logout logout`; `GET /me me`; `PUT /password changePassword`; `PUT /profile updateProfile` | 无方法 guard；SecurityConfig 对 login/captcha 放行，其余认证；AuthService 加载旧 roles/permissions 并签发历史 JWT | E-AUTH；`AuthController.java:30-113` |
| `DepartmentController` `/api/admin/departments`、`/api/departments` | `GET /tree tree`; `POST / createDepartment`; `PUT /{id} updateDepartment`; `DELETE /{id} deleteDepartment` | tree：`department:manage,user:manage,*`；写：`department:manage,*`；两个基路径均可直达 | E-ORG；`DepartmentController.java:26-59` |
| `PermissionController` `/api/admin/permissions`、`/api/permissions` | `GET / list`; `GET /tree tree`; `POST / create`; `PUT /{id} update`; `DELETE /{id} delete` | list/tree：`role:view,role:manage,user:manage,security:manage,*`；写：`role:manage,*`；当前允许任意字符串权限 CRUD | E-ORG；`PermissionController.java:30-121` |
| `RoleController` `/api/admin/roles`、`/api/roles` | `GET / listRoles`; `POST / createRole`; `PUT /{roleId} updateRole`; `DELETE /{roleId} deleteRole`; `GET /{roleId}/permissions listRolePermissions`; `PUT /{roleId}/permissions updateRolePermissions`; `GET /{roleId}/users listRoleUsers`; `POST /{roleId}/users assignRoleToUser`; `DELETE /{roleId}/users/{userId} removeRoleFromUser` | list/permission read/member read：`role:view,role:manage,user:manage,*`；角色写：`role:manage,*`；成员写：`role:manage,user:manage,*`；RoleService 以旧 `ADMIN`/`*` 保护 | E-ORG；`RoleController.java:27-101` |
| `UserController` `/api/admin/users` | `GET / listUsers`; `GET /{id} getUser`; `POST / createUser`; `PUT /{id} updateUser`; `PATCH /{id}/status` 或 `PUT /{id}/status updateStatus`; `DELETE /{id} deleteUser`; `POST /{id}/reset-password resetPassword`; `GET /import-template downloadImportTemplate`; `POST /import importUsers`; `GET /export exportUsers` | 类：`user:manage,*`；Service 校验用户/部门/角色，旧管理员 ID=1 受保护；导入复用创建逻辑，导出固定最多 100 条 | E-ORG；`UserController.java:42-151` |
| `SnapshotVersionController` `/api/admin` | `PATCH /snapshots/{snapshotId}/status changeStatus`; `POST /snapshots/{snapshotId}/publish publish`; `POST /snapshots/{snapshotId}/revoke revoke`; `GET /datasources/{datasourceId}/version-history versionHistory`; `GET /version-history allVersionHistory`; `GET /datasources/{datasourceId}/published-snapshot publishedSnapshot`; `GET /snapshots/{snapshotId}/audit-logs auditLogs`; `GET /datasources/{datasourceId}/audit-logs datasourceAuditLogs`; `GET /snapshots/{snapshotId}/diff/{compareSnapshotId} compareVersions` | 类：`metadata:manage,*`；Lifecycle Service 做状态机与版本校验，无查看/审核/发布码拆分 | E-META；`SnapshotVersionController.java:34-210` |

### 2.2 当前主体、数据授权、字段保护、查询和审计对象

#### Java 事实对象与服务

| 范围 | 当前 Service / Mapper / DTO / VO / Entity | 当前结论 |
|---|---|---|
| 用户、角色、部门 | `user/service/{AuthService,UserService,RoleService,DepartmentService}.java` 及 `impl/{AuthServiceImpl,UserServiceImpl,RoleServiceImpl,DepartmentServiceImpl}.java`；`user/mapper/{UserMapper,RoleMapper,UserRoleMapper,PermissionMapper,RolePermissionMapper,DepartmentMapper}.java`；`user/entity/dto/{LoginDTO,UserCreateDTO,UserUpdateDTO,RoleSaveDTO,RoleUserAssignDTO,DepartmentCreateDTO,DepartmentUpdateDTO,PermissionSaveDTO,StatusUpdateDTO,ChangePasswordDTO,ProfileUpdateDTO}.java`；`user/entity/vo/{LoginVO,CurrentUserVO,UserVO,DepartmentTreeVO,PermissionTreeVO,ResetPasswordVO}.java`；Entity 为 `SysUser`、`SysRole`、`SysDepartment`、`SysPermission`、`SysUserRole`、`SysRolePermission`。 | 当前角色与功能关系全部来自旧 `sys_*` 体系；账号和真实部门是需要保留的非权限基础事实。 |
| 数据源与数据源授权 | `datasource/service/{DatasourceService,DatasourceAccessService,DatasourceReadinessService,DatasourceSecretService}.java` 及实现；`DatasourceAccess`、`DatasourceSecret`、`Datasource`、`DatasourceReadinessVO`、`DatasourcePermissionDecisionVO`；`DatasourceAccessMapper`、`DatasourceMapper`；新权限模块另有 `DatasourcePermissionService`、`DatasourcePermissionVO`。 | 存在两套数据源授权入口：`/api/admin/datasources/{id}/access` 的用户批量入口和 `/api/admin/datasource-access` 的用户/角色/部门入口；计算读旧 `datasource_access`，且 `*` 可直接获得数据源查询权。 |
| 策略与权限计算 | `permission/service/{DatasourcePermissionService,AccessPolicyService,PermissionCalculator,DataMaskingService,AccessApprovalService}.java`；实现 `DatasourcePermissionServiceImpl`、`AccessPolicyServiceImpl`、`PermissionCalculatorImpl`、`DataMaskingServiceImpl`、`AccessApprovalServiceImpl`；Mapper 为 `DatasourceAccessPolicyMapper`、`PermissionChangeLogMapper`、`AccessApprovalRequestMapper`；DTO 为 `DatasourcePermissionGrantDTO`、`AccessPolicyCreateDTO`、`AccessPolicyBatchDTO`；VO 为 `DatasourcePermissionVO`、`PermissionContextVO`、`AccessPolicyVO`。 | `PermissionCalculatorImpl` 收集旧角色、旧部门和旧用户主体，读取旧策略，空策略构造 `UNRESTRICTED`；当前数据模型包含优先级和字符串行条件。 |
| 字段保护 | `DataMaskingServiceImpl` 支持 PHONE/ID_CARD/EMAIL/BANK_CARD/NAME；`PermissionContextVO.MaskColumnItem`；元数据侧 `MetadataCatalogController` 提供 `/mask-candidates` 确认/拒绝，前端 `GovernanceFieldsView` 的“脱敏候选” Tab 使用它。 | 没有独立的字段保护事实表、Controller 或 `security:mask:view/manage` 校验点；当前保护操作分散在元数据标签候选、旧策略和查询结果处理。 |
| 访问审批 | `AccessApprovalRequest`、`AccessApprovalService`、`AccessApprovalServiceImpl`、`AccessApprovalRequestMapper`；前端 `AccessApprovalView`；V41 建表。 | 管理队列和本人列表共用 `/api/admin/access-approvals`；后端已把非安全管理员范围收窄到本人，但没有独立“我的申请”路径和负责源审批判定。 |
| 查询与结果 | `QueryTaskServiceImpl`、`ConversationServiceImpl`、`ConversationContextSummaryServiceImpl`、`PythonAgentClientImpl`；`QueryAskDTO`、`AgentExecuteRequest`、`ConversationContextDTO`、`QueryHistoryQuery`；`QueryTaskVO`、`ConversationMessageVO`；`QueryTaskMapper`、`ConversationMapper`、`ConversationMessageMapper`、`ConversationContextSummaryMapper`。 | Java 发起查询时计算一次旧权限上下文；任务恢复时按旧权限重新脱敏和隐藏 SQL。任务归属有检查，但 SSE 建连缺少归属检查。`canExport=false` 只表示禁用产品提供的导出动作；任务详情仍需返回用户当前获准查看的脱敏结果，导出权限不是阻止复制/抓取已展示数据的保密边界。 |
| 审计与血缘 | `AuditLogServiceImpl`、`LineageServiceImpl`、`LineageEdgeServiceImpl`、`AlertRuleServiceImpl`；`AuditLogQueryDTO`、`LineageCreateRequest`、`ColumnMappingItem`；`AuditLogVO`、`AuditStatsVO`、`LineageTableVO`、`LineageColumnVO`、`ImpactAnalysisVO`、`LineageGraphVO`、`LineageEdgeVO`；`QueryAuditLogMapper`、`QueryLineageTableMapper`、`QueryLineageColumnMapper`。 | 审计列表/详情/统计/慢查询均存在，但 Service 只按调用参数查询，不按 IAM-SIMPLE-1 负责源过滤；审计导出没有 Java API 或前端按钮。 |

### 2.3 旧表、字段、索引和 migration 盘点

| 表/字段范围 | 当前字段、索引、来源 migration | B0 结论 |
|---|---|---|
| `sys_role` | `id, role_code, role_name, description, status, created_at`；`uk_sys_role_code`；V1；V2 初始化 `USER/ANALYST/DATA_MANAGER/SECURITY_MANAGER/ADMIN`。 | 旧角色定义，B6 删除权限专属角色数据；不删除 `sys_user`。 |
| `sys_permission` | `id, permission_code, permission_name, module, description`；`uk_sys_permission_code`；V1；V2/V4/V8/V13/V19/V25/V27/V28/V34/V36 逐步插入 `*`、旧组织/数据源/元数据/知识/字段/安全/Prompt/AI 码。 | 旧功能目录；目标 54 码即使文字相同也只在新目录重新建立。 |
| `sys_user_role` | `id, user_id, role_id`；`uk_sys_user_role(user_id,role_id)`、`idx_sys_user_role_user(user_id)`、`idx_sys_user_role_role(role_id)`；V1、V2。 | 旧用户-角色关系，B6 删除关系数据；账号本身保留。 |
| `sys_role_permission` | `id, role_id, permission_id`；`uk_sys_role_permission(role_id,permission_id)`、`idx_sys_role_permission_role(role_id)`、`idx_sys_role_permission_permission(permission_id)`；V1 及后续权限 seed migration。 | 旧角色-功能关系，B6 删除关系数据。 |
| `sys_user.department_id` / `sys_department` | 用户主部门标识；部门 `id,parent_id,dept_name,dept_code,sort_order,status,created_at`；`uk_sys_department_code`、`idx_sys_department_parent(parent_id)`；V1。 | 真实账号、密码、部门树和组织事实保留；新 IAM 只引用其标识并重新建立关系。 |
| `datasource_access` | V4 初始 `user_id, granted_by, granted_at, expires_at`；V25 改为 `subject_type, subject_id` 并增加 `can_query, can_export, can_view_sql`，索引改为 `uk_datasource_subject(datasource_id,subject_type,subject_id)`；V42 增加 `access_effect`；V25 `idx_policy...` 不属于本表。 | 旧数据源查询关系和结果能力关系，B6 删除活动关联数据；历史审批/审计按保留规则处理。 |
| `datasource_access_policy` | `id,datasource_id,subject_type,subject_id,table_name,column_name,access_type,mask_strategy,row_filter_expression,created_by,created_at,updated_at`；`idx_policy_datasource_subject(datasource_id,subject_type,subject_id,table_name)`、`idx_policy_table(datasource_id,table_name)`；V25；V40 增加 `priority,valid_from,valid_until,time_schedule` 和 `idx_policy_priority(datasource_id,priority)`。 | 旧表列行策略，B6 删除活动策略和旧字段；新记录条件必须改为结构化数据。 |
| `permission_change_log` | `change_type,target_type,target_id,subject_type,subject_id,datasource_id,old_value,new_value,operator_id,reason,created_at`；`idx_pcl_type/change`, `idx_pcl_time`, `idx_pcl_datasource`, `idx_pcl_subject`；V40。 | 历史权限变更证据保留为只读历史，不成为新计算输入；旧写入服务在 B6 删除。 |
| `access_approval_request` | `requester_id,datasource_id,table_name,column_name,request_reason,requested_duration,status,approver_id,approved_at,expires_at,reject_reason,created_at`；`idx_aar_status/requester/datasource/expires`；V41。 | 历史审批记录保留为只读历史；活动申请和审批链路在 B1-B4 使用新 IAM 关系。 |
| `query_task.masked_fields` | V26 增加 JSON 字符串字段，保存 Python 标记的结果脱敏字段；`query_task` 本身是业务查询资产。 | `query_task`、结果、会话和该历史字段保留；B3 复查结果，不删除业务任务。 |

Migration 关联完整盘点：V1 建旧用户/角色/权限四表，V2 初始化旧角色与管理员，V4 建旧数据源授权，V8/V13/V19/V25/V27/V28/V34/V36 写旧权限码或关系，V40 增加旧策略增强和权限审计，V41 增加旧审批，V42 补旧授权效果。已执行 Flyway 文件一律保留，B1/B2/B6 只能使用新的前向 migration。

规格差异也已冻结：`specs/015-permission-security/data-model.md` 的示例声明了数据库外键，但当前 V1/V4/V25/V40/V41 migration 实际没有创建外键；B0 遵循仓库规则，S1 仍不创建数据库外键。规格中的 `datasource_access`、`datasource_access_policy`、`can_query/can_export/can_view_sql` 和字符串 `row_filter_expression` 是现行实现的历史形状，不是 S1 新表合同。

### 2.4 权限加载、JWT、会话和缓存

当前链路为：

```text
登录 -> UserDetailsServiceImpl 查询 sys_user
     -> RoleMapper 查询 sys_user_role + sys_role
     -> UserMapper 查询 sys_user_role + sys_role_permission + sys_permission
     -> LoginUser.permissions / authorities
     -> JwtTokenProvider 写 roles、permissions、tokenVersion
     -> JwtAuthenticationFilter 读取 JWT，检查 jwt:blacklist:{jti} 和 user:token-version:{uid}
     -> Spring @PreAuthorize / UserContext.currentPermissions()
```

真实证据和问题如下：

- `UserMapper.selectPermissionCodesByUserId` 通过 `sys_user_role -> sys_role_permission -> sys_permission` 读出权限码；`UserDetailsServiceImpl` 遇到 `*` 时再读全量 `sys_permission` 并加入 authorities（E-AUTH、`UserMapper.java:33-42`、`UserDetailsServiceImpl.java:76-103`）。
- JWT 当前 claim 有 `uid`、`tokenVersion`、`realName`、`roles`、`permissions`；过滤器会重新加载用户详情，因此 claim 中权限不是唯一来源，但新请求仍由旧表提供 authorities（E-AUTH）。
- 当前 JWT 失效键为 `jwt:blacklist:{jti}`、`user:token-version:{userId}`；登录失败/锁定另外使用 `login:fail:{username}`、`login:auto-lock:{username}`、`login:auto-lock:ttl:{username}`。账号安全键与权限键在 B6 分别处理。
- `PermissionCalculatorImpl` 建立 `userId:datasourceId` 的进程内 Caffeine 缓存，10 秒写过期、5 秒访问过期；事务提交后按用户/数据源清理。`CacheConfig` 也把 Spring Cache 固定为 Caffeine（E-CACHE）。这不满足新权限只使用 Redis 的冻结决策。
- Java 当前业务 Redis 还使用 `fallback:chunks:{datasourceId}:{snapshotId}:{questionHash}`、`glossary:approved`；Python 使用 `agent:user:{userId}:*`、`agent:fewshot:{datasourceId}:examples` 和问题向量缓存。它们不作为新权限事实，但 B3 必须先按权限快照过滤进入模型的内容，并以修订版本隔离可能含 SQL 的 few-shot 数据。
- 当前未在代码中发现 `conv:history:*` 实现；会话事实由 `conversation`、`conversation_message` 和摘要表保存。不能把不存在的缓存当作已验证能力。

### 2.5 Java 到 Python 的权限字段与安全边界

| 方向/位置 | 当前字段或校验 | B0 判断 |
|---|---|---|
| Java → Agent | `AgentExecuteRequest` 有 `datasourceId,userId,activeSnapshotId,userPermissions`；`PythonAgentClientImpl.buildUserPermissionsMap` 只发送 `allowedTables,tableScopeMode,deniedColumns,rowFilters,maskColumns`。 | 缺协议版本、权限修订、计算时间、下一生效边界、明确资源来源、结果能力、结构化记录条件和字段可见状态。 |
| Python Agent | `agent.UserPermissions` 只建模上述五类字段；`ExecuteRequest` 要求 `userPermissions` 和 `activeSnapshotId`。 | Python 能做旧字段的 AST 检查，但不能验证新快照身份、版本或资源来源。 |
| Java → RAG | `PythonAgentClientImpl` 将当前快照的已索引 chunk 传给 Agent；`rag.RetrieveRequest` 只有数据源、问题、topK、快照、置信度和 `fallbackChunks`。 | RAG 请求没有用户权限快照；Milvus 只有数据源/快照/审核/治理状态过滤，未按表列授权过滤。 |
| SQL AST | Agent `sql_validator` 将旧 `user_permissions` 展开成表白名单、行条件、拒绝字段和脱敏字段，再调用 `validate`、`rewrite`。 | AST 是必要边界但输入仍是旧平铺字段；记录条件在当前实现中是字符串。 |
| 直接 SQL 执行 | `sandbox.ValidateRequest` 有旧表权限字段；`sandbox.ExecuteRequest` 只有 `maskColumns`，没有表白名单、字段白名单、记录条件或协议版本。 | 直接调用 `/internal/sql/execute` 不能单独证明权限安全；B3 必须让执行入口只接受由 Java 构造并与 `taskId + permissionRevision + activeMetadataSnapshotId` 绑定的完整 S1 执行上下文。 |
| 内部入口 | Python `main.py` 为 `/internal/query`、`/internal/rag`、`/internal/sql` 等统一加 `X-Internal-Token`；Java `SecurityConfig` 对 `/internal/**` 放行给内部令牌逻辑。当前 Python 路由装饰器共 27 个，含配置和健康路由。 | 内部令牌只证明服务调用身份，不证明最终用户数据权限；不能替代请求级 IAM 快照。 |
| 模型上下文 | SQL 生成 Prompt 使用 `schema_context`、问题、会话历史、摘要、few-shot；图表节点使用查询结果；SQL 自校正使用原始 SQL、错误和 Schema。 | B3 必须增加 LLM Context Firewall：所有 Schema、chunk、关系、few-shot、术语、历史、摘要、错误和图表数据先按快照过滤/脱敏，绑定参数永不进入模型。 |

> **取代说明（2026-09-25）**：本节表格是 B0 评审时点的状态快照，其中的**内部令牌**相关描述已被后续实现取代，阅读时不要当作当前行为：
> `InternalMetadataController` / `PromptInternalController` / `InternalAiConfigController` 三处方法内校验（`requireInternal`、`validateInternalCall`、`getRawConfig` 的 header 判断）**已全部删除**，改由 `common/security/InternalTokenFilter` 在 `/internal/**` 上统一保护；`SecurityConfig` 不再对 `/internal/**` 使用 `permitAll()`，而是要求过滤器写入的 `ROLE_INTERNAL`（fail-closed）。令牌不再有任何默认值，缺失/含空白/短于 32 字符时两个服务都拒绝启动。本文档的 B6 删除/保留基线不受影响。

### 2.6 `specs/015-permission-security` 与 B0 的关系

| 文件 | 已读到的现行内容 | B0 处理 |
|---|---|---|
| `spec.md` | FR-001～FR-013 以旧角色、数据源授权、行列策略和 Prompt 约束描述需求；假设字段是 `allowedTables/deniedColumns/maskColumns/rowFilters`。 | 作为旧需求背景和验收来源；B1-B4 以 54 个 S1 码、结构化资源快照和本文件验收项替代平铺字段。 |
| `plan.md` | 以旧 `module/permission` 包、`PermissionCalculator`、`PermissionContext`、5 个预设角色和 V11/V15 类 migration 规划实现。 | 作为历史实现计划；不作为 S1 的表、包、权限算法或 migration 编号依据。 |
| `data-model.md` | 以 `datasource_access`、`datasource_access_policy` 和含 FK 的关系图建模，`row_filter_expression` 为字符串。 | 与 §2.3 的真实 migration 对照后，冻结为旧形状；S1 使用 §4 的独立新表和结构化条件。 |
| `contracts/api.md` | 以 `/api/admin/datasource-access`、`/api/admin/access-policies` 为管理 API，统一要求旧 `security:manage`，请求体传旧 `permissionContext`。 | 作为当前 Controller/API 证据；B3/B4 使用 §6 S1 合同，旧路径按 §7 B6 处理。 |
| `tasks.md` | T001～T034 均已标记完成，包含旧表、旧 Calculator、旧 AST 注入和旧前端权限矩阵。 | “已完成”只表示当前仓库存在这些实现；不表示 S1 已实施。B1-B5 新测试必须重新覆盖隔离、默认拒绝、资源范围和切换验收。 |

## 3. 54 个目标功能码消费矩阵

### 3.1 矩阵字段说明

- `范围`：`源` 表示必须按后台角色负责源匹配；`全` 表示功能本身为全局功能；查询类的数据范围仍由数据授权单独决定。
- `依赖`：列出查看/维护/审核/发布之间的依赖。维护包含查看；审核、发布各自需要查看但互不包含；SQL/导出依赖使用问数和当前结果授权。
- `覆盖`：`详/批/统/导` 分别表示详情、批量、统计、导出入口，`—` 表示源码没有该语义入口。
- `消费结论`：`有` 只表示真实页面/API 动作存在；`部分` 表示页面有动作但缺独立目标语义或只有占位动作；`无` 表示没有真实消费，不能为其编接口。

### 3.2 目标功能码矩阵

| # / 功能码 | 中文名称与具体作用 | 域 / 工作区 / 路由 / Tab / 按钮 | API 方法与完整路径；当前 Controller/Service 强制点 | 范围 / 依赖 / 覆盖 / 消费结论 | B1-B4 替代或改造位置；源码证据 |
|---:|---|---|---|---|---|
| 1 `query:use` | 使用问数：对有数据授权的数据源提问、看本人历史、反馈或取消本人任务。 | 用户端 `/query`；结果 Tab：表格结果、SQL、图表、可信依据；发送、停止、重试、继续等待、新建会话。 | `POST /api/query/ask`；`GET /api/query/tasks/{taskId}`；`POST /api/query/tasks/{taskId}/cancel`；`POST /api/query/tasks/{taskId}/feedback`；会话列表/消息/历史/归档和 `GET /api/query/tasks/{taskId}/stream`。当前无 guard；ask 有旧数据源授权/readiness/快照检查，任务和会话检查用户归属，SSE 未检查归属。 | 源=数据源查询范围；无功能前置；详=任务/会话，批=—，统=—，导=结果能力；消费结论=有（目标码直接消费=否）。 | B3 固化查询快照、任务恢复、SSE 归属和当前授权复查；B4 改问数入口/按钮。E-QUERY、E-NAV。 |
| 2 `query:sql:view` | 查看 SQL：只看本人查询的安全 SQL，不显示权限参数或他人内容。 | `/query` 结果“SQL”Tab；当前没有独立前端权限判断。 | `GET /api/query/tasks/{taskId}` 返回任务详情；`QueryTaskServiceImpl.getTaskResult` 依据旧 `canViewSql` 置空 SQL；没有独立目标码。 | 源=随查询任务；依赖=query:use；详=有，批/统/导=—；消费结论=部分。 | B3 将 SQL 查看作为结果能力；Java 先校验当前快照、移除绑定参数和敏感来源；B4 控制 Tab 可见性。E-QUERY、`QueryResult.vue:59-62,104-108`。 |
| 3 `query:export` | 导出结果：只能导出当前仍获准查看的脱敏结果。 | `/query` 结果“图表”Tab 的“导出 PNG”和结果栏“导出 CSV”。 | 无独立导出 API；`useQueryExport.exportCsv` 在浏览器生成 CSV，UI 仅看旧 `latestResult.canExport`；PNG 函数明确为开发中。任务详情为展示结果仍返回当前获准的脱敏 data，因此 export 只能控制产品提供的导出功能并记录审计，不是数据保密边界。 | 源=随查询结果；依赖=query:use；详=任务详情，批=—，统=—，导=CSV 有/PNG 部分；消费结论=部分。 | B3 由 Java 保证展示与导出都只使用当前获准的脱敏结果，B4 只保留真实导出动作并按新能力禁用；不得以按钮代替后端当前权限复查。E-QUERY。 |
| 4 `admin:workbench:view` | 查看工作台：查看获准后台概况、待办和任务入口。 | 工作台 `/admin/workbench`；无固定 Tab；进入智能问数、查看数据源、问题中心等入口。 | `GET /api/admin/dashboard/stats`；`GET /api/admin/datasources/simple`；`GET /api/admin/datasources/readiness/batch`。当前 Dashboard 只允许 `*`，数据源辅助接口使用旧宽表达式。 | 全=功能入口；依赖=各目标工作区权限；详=卡片/数据源，批=readiness batch，统=dashboard stats，导=—；消费结论=有。 | B1 固定全局功能目录；B4 工作台按可见域和负责源过滤卡片，后端同步检查。E-SYSTEM、E-DS、`AdminHomeView.vue:101-213`。 |
| 5 `datasource:view` | 查看数据源：查看负责源的基本配置、连接状态和安全摘要，不显示密码/业务记录。 | 数据接入→数据源 `/admin/data-sources`；详情 `/admin/data-sources/:id`；驾驶舱、连接、刷新。 | `GET /api/admin/datasources`、`/{id}`、`/{id}/readiness`、`/simple`、`/readiness/batch`；当前列表/详情受旧 `datasource:manage,*` 或宽表达式保护，Service 只做对象状态校验。 | 源=目标源负责范围；依赖=无；详=有，批=readiness，统=readiness，导=—；消费结论=有。 | B1 建立查看码和角色负责源；B4 详情/批量/摘要统一检查。E-DS。 |
| 6 `datasource:manage` | 维护数据源：新增、编辑、启停、删除、测试连接；不授予业务查询权。 | 数据接入→数据源；新增数据源、编辑、连接、启用/禁用、删除。 | `POST /api/admin/datasources`、`PUT /{id}`、`DELETE /{id}`、`PATCH /{id}/status`、`POST /test-connection`、`POST /{id}/test-connection`；当前类 guard 旧码，Service 做连接/删除和密钥处理，创建未绑定新负责源。另有旧 `/ {id}/access` 三个入口。 | 源=数据源管理范围；依赖=datasource:view；详=有，批=旧用户授权 batch，统=—，导=—；消费结论=有。 | B1 角色负责源关系；B4 去掉数据源页旧授权入口，创建事务按新规则绑定负责源但不授予业务数据权。E-DS。 |
| 7 `metadata:collect:view` | 查看采集任务：看负责源的采集进度、记录和同步计划。 | 数据接入→采集任务 `/admin/collections`；Tab：采集任务、同步计划；刷新。 | `GET /api/admin/metadata/sync-tasks`、`GET/PUT /api/admin/system/sync-schedule`；当前全由 `metadata:manage,*` 保护。 | 源；依赖=datasource:view；详=任务/快照，批=—，统=—，导=—；消费结论=有。 | B1/B4 把任务查看与采集执行拆开并在同源负责范围校验。E-META、`CollectionsView.vue:37-39`。 |
| 8 `metadata:collect:run` | 执行采集：启动负责源采集并配置自动同步计划。 | 同上；Tab：采集任务/同步计划；发起采集、保存配置。 | `POST /api/admin/metadata/sync`、`PUT /api/admin/system/sync-schedule`；当前旧 `metadata:manage,*`，Service 只校验数据源和请求。 | 源；依赖=metadata:collect:view；详=任务详情，批=—，统=—，导=—；消费结论=有。 | B4 按动作拆 guard；自动计划与手动采集共用同一负责源强制点。E-META。 |
| 9 `metadata:view` | 查看资产结构：看负责源表、字段、说明、关系，不浏览业务原始记录。 | 数据资产→资产目录 `/admin/assets`；Tab：正式资产目录、快照表浏览；资产详情、关系图、下游影响。 | `GET /api/admin/catalog/search`、实体详情/关系/下游/标签，`GET /api/admin/metadata/snapshots/{id}/tables` 及 columns；当前旧 `metadata:manage,*`。 | 源；依赖=无；详=实体/表/字段，批=—，统=—，导=—；消费结论=有。 | B4 目录详情、关系和快照表读取统一用 `metadata:view + 负责源`，过滤业务采样值。E-META、`AssetsView.vue:116-150`。 |
| 10 `metadata:release:view` | 查看元数据版本：看快照、审核状态、版本差异和审计记录。 | 数据资产→版本发布 `/admin/releases`；Tab：候选快照、版本历史、版本差异；快照详情/差异。 | `GET /api/admin/metadata/snapshots`、详情、`GET /api/admin/datasources/{id}/version-history`、published、audit-logs、diff；当前旧 `metadata:manage,*`。 | 源；依赖=无；详=有，批=—，统=—，导=—；消费结论=有。 | B1/B4 拆分查看/审核/发布；所有 snapshotId 校验其数据源与负责源。E-META、`ReleasesView.vue:281-353`。 |
| 11 `metadata:release:review` | 审核快照：批准或驳回发布申请，不执行发布。 | 版本发布候选 Tab；详情中的批准/驳回或开始检查后状态动作。 | `PATCH /api/admin/snapshots/{snapshotId}/status`、`GET /api/admin/snapshots/{snapshotId}/review-records`；当前旧 `metadata:manage,*`，Lifecycle 校验状态但无独立审核码。 | 源；依赖=metadata:release:view；详=快照详情，批=—，统=—，导=—；消费结论=有。 | B4 只允许 review 角色操作状态，发布码不随审核自动获得；记录审核审计。E-META。 |
| 12 `metadata:release:publish` | 发布/回滚快照：使已审核快照上线或按版本规则撤回/回滚。 | 版本发布候选/历史/差异 Tab；发布、撤回、日志。 | `POST /api/admin/snapshots/{snapshotId}/publish`、`POST /api/admin/snapshots/{snapshotId}/revoke`；当前旧 `metadata:manage,*`。 | 源；依赖=metadata:release:view；详=有，批=—，统=—，导=—；消费结论=有。 | B3 联动 active snapshot 和权限 revision；B4 发布按钮只对新码开放。E-META。 |
| 13 `governance:view` | 查看治理总览：质量评分、问题分布和治理进度。 | 数据治理→治理总览 `/admin/governance`；无固定 Tab；返回版本发布。 | `GET /api/admin/quality-issues` 或快照问题列表等；当前 Controller 类 guard `metadata:manage,*`，Service 有快照筛选但无负责源。 | 源；依赖=metadata:view；详=问题详情，批=—，统=质量结果，导=—；消费结论=有。 | B4 将总览数据按负责源裁剪，业务原值不返回。E-META、`QualityDashboard.vue`。 |
| 14 `governance:check` | 执行质量检查：对负责源/快照运行规则并生成问题。 | 治理总览、版本发布；开始检查/质量检查。 | `POST /api/admin/snapshots/{snapshotId}/quality-check`；当前旧 `metadata:manage,*`，Service 校验快照和规则状态。 | 源；依赖=governance:view、metadata:release:view；详=快照，批=—，统=质量分数，导=—；消费结论=有。 | B4 将检查按钮绑定新码并校验同源快照。E-META。 |
| 15 `governance:issue:view` | 查看质量问题：问题、责任人和处理记录。 | 数据治理→问题中心 `/admin/governance/issues`；筛选、详情、审核记录。 | `GET /api/admin/snapshots/{snapshotId}/quality-issues`、`GET /api/admin/quality-issues`、`GET /api/admin/snapshots/{snapshotId}/review-records`；当前旧 `metadata:manage,*`。 | 源；依赖=governance:view；详=有，批=—，统=问题计数，导=—；消费结论=有。 | B4 列表、详情、复查均统一负责人范围；`assigneeId` 只作筛选不改变授权。E-META、`IssueList.vue:380-561`。 |
| 16 `governance:issue:manage` | 处理质量问题：确认、解决、驳回、重新打开、批量处理和分派。 | 问题中心；确认、驳回、解决、重新打开、批量确认/驳回、分派。 | `PATCH /api/admin/quality-issues/{issueId}/status`、`PATCH /api/admin/quality-issues/batch-status`、`POST /api/admin/quality-issues/{issueId}/assign`；当前旧 guard，Service 校验状态流转和部分成功，未做新负责源。 | 源；依赖=governance:issue:view；详=有，批=有，统=—，导=—；消费结论=有。 | B4 批量和单项共用同一 resolver，按目标快照所属源强制校验。E-META。 |
| 17 `governance:rule:view` | 查看治理规则：规则、维度、严重级别、启用状态和资产状态。 | 数据治理→规则与状态 `/admin/governance/rules`；Tab：质量规则、资产状态。 | `GET /api/admin/quality-rules`、快照表/列查询；当前旧 `metadata:manage,*`。 | 源；依赖=governance:view；详=表/列，批=—，统=规则状态，导=—；消费结论=有。 | B4 读取规则和资产状态按负责源处理；全局内置规则仅读。E-META、`StatusEditor.vue:236-256`。 |
| 18 `governance:rule:manage` | 维护治理规则与资产治理状态：调整规则启停和获准状态。 | 同上；启停规则、更新表/列状态、批量状态。 | `PATCH /api/admin/quality-rules/{ruleId}`、`PATCH /snapshots/{id}/tables/.../governance-status`、`PATCH /columns/...`、`PATCH /tables/.../batch-governance-status`；当前旧 guard。 | 源；依赖=governance:rule:view；详=有，批=有，统=—，导=—；消费结论=有。 | B2 固化治理状态对授权/RAG 的硬阻断；B4 单条/批量共用负责源校验。E-META。 |
| 19 `governance:field:view` | 查看字段治理：字段说明、标签、可信度和反馈。 | 数据治理→字段治理 `/admin/governance/fields`；Tab：标签、可信度、反馈审核、脱敏候选。 | GET `/api/field-tags/column/{id}`、`/by-tag/{code}`、`/predefined`、`/api/field-confidence`、`/{id}`、`/batch`、`/{id}/events`、`/api/feedback-reviews`；当前部分 GET 无 guard，其他旧 `field-tag:manage,*`。 | 源；依赖=metadata:view；详=字段/事件，批=confidence batch，统=趋势，导=—；消费结论=有。 | B4 增加独立 view 码并按发布快照/负责源过滤。E-META、`GovernanceFieldsView.vue:137-147`。 |
| 20 `governance:field:manage` | 维护字段治理/审核反馈：标签、可信度、自动打标、反馈处理；字段保护另算。 | 同上；导入标签、自动打标、批量打标、设置可信度、反馈通过/驳回。 | `POST /api/field-tags`、`/batch`、`DELETE /api/field-tags/{id}`、`PUT /api/field-confidence/{id}`、`POST /api/admin/fields/import-tags`、`/auto-tag`、`POST /api/feedback-reviews/{id}/approve` 和 `POST /api/feedback-reviews/{id}/reject`；当前旧 `field-tag:manage,*`。 | 源；依赖=governance:field:view；详=字段详情，批=打标/反馈，统=趋势，导=—；消费结论=有。 | B4 替换旧码并确保标签/可信度写入只接受负责源和发布快照。E-META。 |
| 21 `glossary:view` | 查看业务术语：词、定义、同义词和关联字段。 | 语义中心→业务术语 `/admin/semantics/glossaries`；术语表、术语详情、关联字段。 | `GET /api/admin/glossary`、`GET /{glossaryId}/terms`、`GET /terms/{termId}/linked-columns`；当前类 guard 旧 `metadata:manage,*`。 | 关联源存在时=源；未绑定源时=全；依赖=无；详=术语/关联，批=—，统=—，导=—；消费结论=有。 | B4 查看只读按关联源裁剪，未绑定术语按全局语义展示。E-GLOSSARY。 |
| 22 `glossary:manage` | 维护业务术语：新建/修改/删除词和关联对象，不自动通过审核。 | 同上；新建、编辑、删除术语表/词、关联/解除字段、提交审核、退回草稿。 | `POST/PUT/DELETE /api/admin/glossary`、`POST/PUT/DELETE /terms`、`POST /terms/{id}/submit`、`/revert`、`/link-column`、`/unlink-column`；当前全部旧 `metadata:manage,*`。 | 源/全；依赖=glossary:view；详=有，批=—，统=—，导=—；消费结论=有。 | B4 拆维护和审核；关联多个源时逐源校验。E-GLOSSARY。 |
| 23 `glossary:approve` | 审核术语：同意或驳回术语审核申请。 | 业务术语；术语审核动作。 | `POST /api/admin/glossary/terms/{termId}/review`；当前仍由 `metadata:manage,*` 保护。 | 源/全；依赖=glossary:view；详=术语，批=—，统=—，导=—；消费结论=有。 | B4 使用独立审核码，审批者不自动获得维护或查询数据权。E-GLOSSARY。 |
| 24 `knowledge:view` | 查看知识文档：文档、版本、审核记录、切分和索引状态。 | 语义中心→语义知识 `/admin/semantics/knowledge`；Tab：全部文档、审核队列；详情 6 Tab：内容、来源与覆盖、审核记录、版本、切分预览、索引状态。 | `GET /api/admin/knowledge-docs`、`/{id}`、review-tasks、source-snapshots、versions、diff、vector-tasks、preview-chunks；当前类 guard 旧 `knowledge:manage,*`。 | 源；依赖=无；详=有，批=—，统=索引/版本状态，导=—；消费结论=有。 | B4 查看接口拆分并校验文档 datasourceId/快照绑定。E-KNOWLEDGE。 |
| 25 `knowledge:manage` | 维护知识文档：生成/编辑草稿、保存、提交审核，不直接发布。 | 语义知识列表/详情；新建、保存、AI 生成草稿、提交审核。 | `POST/PUT /api/admin/knowledge-docs`、`POST /{id}/generate-draft`、`POST /generate-from-snapshot`、`POST /{id}/submit-review`；当前旧 `knowledge:manage,*`，Service 做状态和版本校验。 | 源；依赖=knowledge:view；详=有，批=生成多文档，统=—，导=—；消费结论=有。 | B4 写动作使用维护码；草稿生成的 Python 调用仍由 Java 负责源和快照校验。E-KNOWLEDGE。 |
| 26 `knowledge:approve` | 审核知识文档：同意或驳回审核申请。 | 语义知识审核队列/详情“审核记录”Tab；通过、驳回。 | `POST /api/admin/knowledge-docs/{id}/approve`、`/{id}/reject`；当前旧 `knowledge:manage,*`。 | 源；依赖=knowledge:view；详=有，批=—，统=—，导=—；消费结论=有。 | B4 独立审核码；审核不自动拥有维护/发布。E-KNOWLEDGE。 |
| 27 `knowledge:publish` | 发布/回滚知识：发布已批准文档并建立索引，按版本规则回滚。 | 语义知识详情；索引状态/版本 Tab；发布、回滚。 | `POST /api/admin/knowledge-docs/{id}/publish`、`POST /{id}/rollback`；当前旧 `knowledge:manage,*`；Python 向量任务由 Java 生命周期控制。 | 源；依赖=knowledge:view；不依赖 approve 作为同一人权限；详=有，批=—，统=索引任务，导=—；消费结论=有。 | B3 保留已验证新向量后清理旧版本的生命周期；B4 拆发布按钮。E-KNOWLEDGE、E-PY。 |
| 28 `prompt:view` | 查看 AI 提示词：模板、启用状态和历史版本。 | 语义中心→Prompt 策略 `/admin/semantics/prompts`；Tab：当前内容、审核流程、版本历史、效果统计。 | `GET /api/admin/prompt-templates`、`/{code}`、`/{code}/versions`、`/effectiveness`；当前查看 guard 把旧 manage/approve 混合。 | 全；依赖=无；详=版本，批=—，统=效果统计，导=—；消费结论=有。 | B4 只读接口使用新查看码，Prompt 内部读取仍只接受内部服务令牌。E-PROMPT。 |
| 29 `prompt:manage` | 维护 AI 提示词：创建/修改、启停、提交审核或回滚，不直接审批。 | Prompt 策略当前内容/版本历史；保存、提交审核、启停、回滚。 | `PUT /api/admin/prompt-templates/{code}`、`POST /{code}/submit`、`PATCH /{code}/enabled`、`POST /{code}/rollback`；当前旧 `prompt:manage,*`。 | 全；依赖=prompt:view；详=版本，批=—，统=—，导=—；消费结论=有。 | B4 保留生命周期依赖，启停/回滚分别记录审计。E-PROMPT。 |
| 30 `prompt:approve` | 审核 AI 提示词：同意或驳回修改。 | Prompt 策略审核流程 Tab；通过、驳回。 | `POST /api/admin/prompt-templates/{code}/approve` 和 `POST /api/admin/prompt-templates/{code}/reject`；当前旧 `prompt:approve,*`，查看类仍混用。 | 全；依赖=prompt:view；详=版本，批=—，统=—，导=—；消费结论=有。 | B4 独立审批码；发布/启停不自动随审批授予。E-PROMPT。 |
| 31 `security:permission:view` | 查看授权配置：谁在负责源被允许/禁止查询哪些数据。 | 权限与组织→授权管理 `/admin/access`；Tab：数据源授权、表列策略、最终权限预览；主体选择、刷新、查看。 | `GET /api/admin/datasource-access`、`GET /api/admin/access-policies`；当前类 guard `security:manage,*`，列表可返回全源主体配置，没有负责源校验。 | 源；依赖=无；详=授权/策略，批=策略 batch 只属写，统=—，导=—；消费结论=有。 | B1 新查看码和负责源查询；B4 Tab 只展示中文授权摘要。E-PERM、`AccessControl.vue:559-733`。 |
| 32 `security:permission:manage` | 维护授权配置：为用户/角色/部门增加、调整、撤销数据范围。 | 授权管理“数据源授权/表列策略”Tab；新增授权、切换允许/禁止、查询/导出/SQL能力、撤销、新增/删除策略。 | `POST/PUT/DELETE /api/admin/datasource-access`、`POST/PUT/DELETE /api/admin/access-policies`、`POST /access-policies/batch`；当前旧 `security:manage,*`，Service 无负责源/本人保护。 | 源；依赖=security:permission:view；详=有，批=策略有，统=—，导=—；消费结论=有。 | B2 新授权/字段/记录关系与修订；B4 表单收敛为“谁、源、表字段、记录、多久”。E-PERM。 |
| 33 `security:mask:view` | 查看字段保护：字段正常显示、隐藏或脱敏及保护策略。 | 授权管理“表列策略”中 MASK；字段治理“脱敏候选”Tab。当前没有独立字段保护 Tab。 | 当前 `GET /api/admin/access-policies` 可看旧 MASK 策略；`GET /api/admin/catalog/mask-candidates` 看候选；均不是独立目标 guard。 | 源；依赖=metadata:view；详=字段策略，批=—，统=候选数，导=—；消费结论=部分（无独立字段保护事实/API）。 | B2 新字段保护事实和预览；B4 把字段治理候选与已生效保护区分。E-PERM、E-META。 |
| 34 `security:mask:manage` | 维护字段保护：确认隐藏/脱敏状态或掩码；不授予字段查询权。 | 字段治理“脱敏候选”；授权管理旧表列策略 MASK。确认生效、拒绝。 | `POST /api/admin/catalog/mask-candidates/{entityId}/confirm` 和 `POST /api/admin/catalog/mask-candidates/{entityId}/reject`；另有旧 `POST /api/admin/access-policies` 的 MASK；当前 `metadata:manage,*` 或 `security:manage,*`。 | 源；依赖=security:mask:view；详=字段，批=—，统=—，导=—；消费结论=部分。 | B2 固化 HIDDEN/MASKED 与治理准入；B4 删除分散写入口，统一字段保护表单。E-PERM、`GovernanceFieldsView.vue:137-171`。 |
| 35 `security:effective:view` | 查看用户实际权限：显示能查什么、不能查什么及每项原因。 | 授权管理“最终权限预览”Tab；选择用户、计算权限。 | `GET /api/admin/datasource-access/decision`；当前旧 `security:manage,*`，调用 `DatasourceAccessService.calculateDecision`，不是统一预览服务。 | 源；依赖=security:permission:view；详=有，批=—，统=计算结果，导=—；消费结论=有。 | B2 统一计算与真实查询共用 Resolver；B4 展示授权来源、记录条件、保护原因和负责源。E-PERM、`AccessControl.vue:683-731`。 |
| 36 `security:approval:view` | 查看访问申请：管理员只看负责源队列，用户只看本人申请。 | 权限与组织→访问审批 `/admin/access/approvals`；Tab：待我审批、已处理、已过期；详情。 | `GET /api/admin/access-approvals`；当前无方法 guard，`security:manage`/`*` 看全量，其他只看 requesterId；没有独立我的申请 API。 | 源；依赖=无；详=有，批=—，统=状态分页，导=—；消费结论=部分。 | B1 新申请关联和负责源；B4 分出“我的申请”与管理员队列两个入口，仍由 Java 过滤。E-PERM、`AccessApprovalView.vue:282-372`。 |
| 37 `security:approval:review` | 审批数据访问：同意/拒绝批准范围内的临时数据权，不审批本人。 | 访问审批待我审批/详情；通过、拒绝。 | `POST /api/admin/access-approvals/{requestId}/review`；当前 `security:manage,*`；Service 只复查治理并写旧临时策略，未检查审批人负责源/本人。 | 源；依赖=security:approval:view；详=有，批=—，统=—，导=—；消费结论=部分。 | B2 新审批关联与临时授权；B4 通过/拒绝强制同源、非本人、子集、期限和禁止规则。E-PERM。 |
| 38 `organization:user:view` | 查看用户：账号资料、主部门、角色和状态。 | 权限与组织→组织与角色 `/admin/access/organization`；Tab：用户；详情、筛选、刷新。 | `GET /api/admin/users`、`GET /api/admin/users/{id}`；当前类 guard `user:manage,*`，没有独立查看码。 | 全；依赖=无；详=有，批=—，统=分页，导=—；消费结论=有。 | B1 新用户主体关系；B4 列表/详情分离查看与维护并隐藏密码/秘密。E-ORG。 |
| 39 `organization:user:manage` | 维护用户：创建/编辑普通账号、启停、重置密码、选主部门和业务角色。 | 用户 Tab；新增、编辑、启用/禁用、重置密码、删除、导入。 | `POST/PUT/DELETE /api/admin/users`、`PATCH /{id}/status` 或 `PUT /{id}/status`、`POST /{id}/reset-password`、`POST /import`；当前旧 `user:manage,*`，Service 校验角色/部门但绑定旧角色。 | 全；依赖=organization:user:view、organization:role:view、organization:department:view；详=有，批=导入，统=分页列表，导=—；消费结论=有。 | B1 新角色关系与本人修改规则；B4 把后台负责源仅交给系统管理员。E-ORG。 |
| 40 `organization:user:export` | 导出用户列表：仅导出允许查看的资料，不含密码和认证秘密。 | 用户 Tab；“导出”。 | `GET /api/admin/users/export`；当前类旧 `user:manage,*`，Controller 强制第一页最多 100 条；无独立导出码。 | 全；依赖=organization:user:view；详=列表，批=—，统=导出固定 100，导=有；消费结论=有。 | B4 以 export 独立校验并复用同一用户可见范围，增加导出审计。E-ORG、`UserController.java:142-151`。 |
| 41 `organization:role:view` | 查看角色：用途、功能组合和成员。 | 组织与角色“角色”Tab；角色定义、成员管理；刷新。 | `GET /api/admin/roles`、`GET /{roleId}/permissions`、`GET /{roleId}/users`；当前旧 `role:view,role:manage,user:manage,*`。 | 全；依赖=无；详=成员/功能，批=—，统=成员数，导=—；消费结论=有。 | B1 新角色定义/成员事实；B4 只读展示中文功能摘要和负责源摘要。E-ORG、`RoleList.vue:322-451`。 |
| 42 `organization:role:manage` | 维护角色：创建/修改角色及功能组合、管理成员，不取得系统管理员身份。 | 角色 Tab；新增、编辑、删除、保存功能、添加/移除成员。 | `POST/PUT/DELETE /api/admin/roles`、`PUT /{roleId}/permissions`、`POST/DELETE /{roleId}/users`；当前旧 `role:manage,*` 或成员写 `role:manage,user:manage,*`，RoleService 保护旧 ADMIN/*。 | 全；依赖=organization:role:view、organization:permission:view；详=有，批=成员操作，统=成员数，导=—；消费结论=有。 | B1 新角色绑定和系统管理员保护；B4 删除旧权限树 CRUD，改中文固定目录。E-ORG。 |
| 43 `organization:department:view` | 查看部门：部门树和成员归属信息。 | 组织与角色“部门”Tab；刷新、展开。 | `GET /api/admin/departments/tree` 或 `/api/departments/tree`；当前 `department:manage,user:manage,*`，两个路径均可调用。 | 全；依赖=无；详=树，批=—，统=—，导=—；消费结论=有。 | B1 保留真实部门实体，新增 IAM 部门数据授权关系；B4 只读树和成员摘要复用同一范围。E-ORG。 |
| 44 `organization:department:manage` | 维护部门：创建、调整、禁用/删除符合组织规则的部门，不自动增加数据授权。 | 部门 Tab；新建子部门、编辑、删除、保存。 | `POST/PUT/DELETE /api/admin/departments` 或 `/api/departments`；当前 `department:manage,*`；Service 校验父子关系/非空，但不阻止操作者改变自身组织路径。 | 全；依赖=organization:department:view；详=树，批=—，统=—，导=—；消费结论=有。 | B1/B2 新部门继承授权；B4 增加本人路径保护和数据授权影响预览。E-ORG。 |
| 45 `organization:permission:view` | 查看功能说明：查看固定 54 项中文作用，不创建/修改功能点。 | 组织与角色“权限项”Tab；当前还允许新建、编辑、删除权限项。 | `GET /api/admin/permissions`、`GET /tree`；当前 read guard 混合旧 role/security/user 码，写 API 也存在。 | 全；依赖=无；详=功能说明，批=—，统=—，导=—；消费结论=部分（列表有，固定目录语义未落实）。 | B1 固定 54 码目录；B4 把 Tab 改成只读中文目录并删除任意权限 CRUD 入口。E-ORG、`OrganizationView.vue:189-285`。 |
| 46 `audit:view` | 查看查询审计：负责源的查询执行记录、状态、慢查询和统计，不开放他人原始结果。 | 运营与平台→查询分析 `/admin/operations/queries`；Tab：查询审计、性能分析；详情、刷新、筛选。 | `GET /api/admin/audit-logs`、`/{id}`、`/slow-queries`、`/stats`；当前 `audit:view,*`，AuditLogService 不按负责源过滤详情/统计。 | 源；依赖=无；详=有，批=—，统=有，导=—；消费结论=有。 | B4 列表/详情/慢查询/统计共用安全审计范围；原始 SQL/结果另按能力保护。E-AUD。 |
| 47 `audit:export` | 导出审计：导出允许查看的安全审计，不含业务敏感原值。 | 查询分析当前无导出按钮，无导出 Tab。 | 没有真实 Java Controller 方法、Service 导出方法或前端 API/按钮。 | 源；依赖=audit:view；详/批/统/导=—；消费结论=无，禁止编造接口。 | B4 需先建立真实安全导出消费，再接入该码；B0 不把不存在的接口列为现状，B1 不得假定已有。E-AUD。 |
| 48 `lineage:view` | 查看数据血缘：表/字段来源关系和影响分析。 | 运营与平台→数据血缘 `/admin/operations/lineage`；图谱、搜索、详情。 | `GET /api/lineage/table/{tableName}`、`/column/{tableName}/{columnName}`、`/impact/...`；目录还调用 `/api/admin/catalog/entities/{id}/lineage`、`/column-lineage`、`/downstream`；当前旧 audit/metadata guard，LineageService 用旧数据源授权。 | 源；依赖=metadata:view；详=实体/字段，批=—，统=影响计数，导=—；消费结论=有。 | B4 统一 view 码、负责源和字段保护；同一资源范围覆盖图谱和影响分析。E-AUD、`DataLineage.vue`。 |
| 49 `lineage:manage` | 维护血缘关系：新增、批量导入、删除允许管理的关系，不修改业务记录。 | 数据血缘；新增关系、批量导入、删除/级联删除。 | `POST/DELETE /api/admin/catalog/lineage`、`POST /api/admin/catalog/lineage/batch`；当前 `metadata:manage,*`，Service 校验实体和关系类型，无新负责源。 | 源；依赖=lineage:view；详=关系，批=有，统=—，导=—；消费结论=有。 | B4 将手工/批量入口统一接入 lineage:manage，删除前检查影响和审计。E-AUD。 |
| 50 `system:runtime:view` | 查看运行状态：服务、连接池和告警配置状态。 | 运营与平台→运行监控 `/admin/platform/runtime`；Tab：服务状态、SQL 连接池、告警规则；刷新。 | `GET /api/admin/system/health`、`GET /sql-pools`、`GET /api/admin/alert-rules`；当前 health/pool 为 `*`，告警为 `audit:view,*`。 | 全；依赖=无；详=连接池/规则，批=—，统=运行状态，导=—；消费结论=有。 | B4 独立 view 码，隐藏密钥/业务数据，告警列表和运行探测不扩大管理权限。E-SYSTEM。 |
| 51 `system:runtime:manage` | 维护运行监控：配置告警、启停规则或重置连接池，并说明影响。 | 运行监控；新增/编辑/启停告警、重置连接池。 | `POST/PUT/PATCH /api/admin/alert-rules`、`POST /api/admin/system/sql-pools/{datasourceId}/reset`；当前告警 `audit:view,*`，重置池 `*`。 | 全；依赖=system:runtime:view；详=规则/池，批=—，统=—，导=—；消费结论=有。 | B4 拆分 view/manage，连接池重置增加系统管理员/影响确认，告警对象不带业务结果。E-SYSTEM。 |
| 52 `operation-log:view` | 查看操作日志：管理员操作记录，只读，不能修改/删除。 | 操作日志 `/admin/platform/operation-logs`；筛选、刷新。 | `GET /api/admin/operation-logs`；当前 `audit:view,*`，Service 支持多条件但无目标码。 | 全；依赖=无；详=日志行，批=—，统=筛选，导=—；消费结论=有。 | B4 使用独立码；权限变更历史以只读安全审计展示，不允许删除。E-SYSTEM。 |
| 53 `system:ai-config:view` | 查看 AI 配置：模型/供应商摘要和运行状态，密钥只显示摘要。 | 运行与平台→AI 配置 `/admin/platform/ai`；自定义 Tab：对话模型、Embedding 模型；查看。 | `GET /api/admin/system/ai-config`、`/providers`；当前已有同名字符串，Controller view 表达式同时允许旧 manage；Python 内部配置读取另由内部令牌保护。 | 全；依赖=无；详=供应商，批=—，统=模型配置，导=—；消费结论=有（目标码直接消费=旧表/旧 authority）。 | B1 新目录同名但新关系独立；B4 前端只读判断改用 Java 返回的 S1 能力摘要。E-SYSTEM、E-PROMPT。 |
| 54 `system:ai-config:manage` | 维护 AI 配置：修改供应商/模型/运行参数，不显示已有密钥原值。 | AI 配置两个 Tab；保存配置、供应商增删改、测试连接、同步模型、检测维度、切换。 | `PUT /api/admin/system/ai-config`、`POST/PUT/DELETE /providers`、`POST /providers/{id}/test`、`/sync-models`、`POST /detect-dimension`；当前已有同名字符串，Controller manage 表达式旧 authority，前端 `AiConfig.vue:117-119` 判断旧权限数组。 | 全；依赖=system:ai-config:view；详=供应商，批=—，统=—，导=—；消费结论=有（目标码直接消费=旧表/旧 authority）。 | B4 改为 S1 能力快照；保留 AI 配置业务资产和密钥安全存储，不让配置查看权泄露密钥。E-SYSTEM。 |


### 3.2.1 `glossary:*` 混合语义定稿（批次 5，2026-09-20）

第 21～23 项的「关联源存在时=源；未绑定源时=全」在批次 5 定稿为：

- **未关联任何数据源**：只校验功能，不推导任何数据源权限；
- **已关联**：查看只返回负责源内的关联字段（已绑定术语至少一个可见关联源才返回，无可见源不返回，
  未绑定术语按全局语义显示）；写操作对全部关联源逐源校验、任一无权整体拒绝；
- **关联/解除字段**：先校验术语现有源，再解析目标实体的**真实**数据源并校验，不采信前端传入；
- **术语表更新/删除**：汇总其下全部术语关联源后逐源校验；
- **审核**：独立 `glossary:approve`，不自动带来维护权或业务查询权。

**范围状态是三态，不是「空集合即未绑定」**（修复轮定稿）：`UNBOUND`（确实没有关联关系）按全局功能；`BOUND`（关联完整）逐源校验；`BROKEN`（**存在**关联但实体丢失、读取失败或缺少 `datasource_id`）——查看列表不返回该术语，写/审核/删除/关联一律 409，术语表含 BROKEN 术语时改删术语表同样 409。术语只要有一条关联解析不出归属即整术语 BROKEN；术语表任一术语 BROKEN 即整表 BROKEN。

注解侧：三个码保持 `FunctionScope.MIXED`；`@IamS1ScopedList` 接受 RESOURCE 或 MIXED 而继续拒绝 GLOBAL，
`@IamS1Global` / `@IamS1Resource` 继续拒绝 MIXED；动态范围由 `GlossaryScopeService` 落实。
完整规则见 `docs/development/guides/DataOcean-IAM-SIMPLE-1鉴权接入设计.md` §11.7.1。

### 3.3 当前目标码缺口汇总

目标目录本身已按主设计提取为 54 个唯一值；但当前 Controller 主要消费旧码、通配符或无细粒度 guard。明确缺口如下：

1. 54 个目标码没有一套统一的 Java S1 Resolver 消费链；同名的 `datasource:manage`、`audit:view`、`knowledge:manage`、`prompt:manage`、`prompt:approve`、`system:ai-config:view`、`system:ai-config:manage` 仍来自旧 `sys_permission` 关系。
2. `security:mask:view/manage` 没有独立字段保护事实/API；`audit:export` 没有真实消费，不能预先定义不存在的接口。
3. 列表之外的详情、批量、统计和导出存在不一致：审计详情/统计无负责源过滤，SSE 没有任务归属检查，用户导出使用维护旧码，数据源存在旧授权入口。查询任务详情返回当前获准的脱敏结果属于展示必需，不与 `query:export` 冲突。
4. 角色负责数据源关系尚未存在；当前 `ROLE` 授权与角色功能可以被分别取出再组合，不能证明“同一绑定”语义。
5. 普通用户“我的申请”没有独立路由/API；审批队列使用 `/api/admin/access-approvals`，只能算部分消费。

这些是 B1-B4 的实施缺口，不是新增的功能码，也不是本次代码修复范围。

## 4. IAM-SIMPLE-1 技术隔离决策

### 4.1 冻结：独立新权限表

选择“独立的新权限表”，不选择在旧表中增加 `protocol_version`。原因是当前代码已经通过实体、Mapper、SQL 注解和 Spring Security 直接读取旧表；在旧表中增加一列会让遗漏一个过滤条件就足以把旧关系带入新计算，无法给 B0 提供强隔离证明。独立表使新 Resolver 的数据库读取集合天然闭合，旧表不具备新实体类型，也没有新 Mapper 的读路径。

新表的关系必须至少分开表达：

- `iam_s1_function`：固定 54 码、中文名、具体作用、业务域、工作区、依赖和状态；不可由管理员随意新增字符串码。
- `iam_s1_role`、`iam_s1_role_function`：新角色定义与固定目录关系；受保护系统管理员角色由服务端识别。
- `iam_s1_user_role`：新用户-角色绑定；绑定保存用户 ID、角色 ID、状态和修订版本。
- `iam_s1_role_datasource`：同一用户-角色绑定对应的后台负责源；不能成为业务查询授权。
- `iam_s1_data_grant`、`iam_s1_data_grant_column`、`iam_s1_row_condition`：明确主体、数据源、表列、允许/禁止效果、授权来源、有效期、部门继承选项和结构化记录条件。
- `iam_s1_field_protection`：数据源/发布快照/表/字段的 `NORMAL/HIDDEN/MASKED` 与保护策略。
- `iam_s1_access_request`、`iam_s1_access_approval`：申请、审批、批准范围、期限、生成授权 ID 和审计关联。
- `iam_s1_permission_revision`、`iam_s1_audit_event`：权限修订和新审计事件；不创建数据库外键。

表名是 B0 的存储边界命名，不代表本次已经创建 migration。实施时由 B1-B4 使用当时可用的前向版本，清理升级和初始化升级必须分开。

### 4.2 新 Resolver 的允许/禁止读取集合

允许读取：

- `iam_s1_function` 的固定目录及依赖；
- `iam_s1_role`、`iam_s1_role_function`、`iam_s1_user_role`、`iam_s1_role_datasource`；
- `iam_s1_data_grant*`、`iam_s1_field_protection`、`iam_s1_access_request/approval`、`iam_s1_permission_revision`；
- 作为资源事实的现行 `sys_user`、`sys_department`、`datasource`、已发布元数据快照、治理状态和知识索引元数据；这些只提供非权限基础或资源准入事实，不提供旧角色授权。

禁止读取、转换、推断或回退到：

- `sys_user_role`、`sys_role_permission`、`sys_permission` 的任何权限关系或同名码；
- `datasource_access`、`datasource_access_policy` 的任何活动授权、行条件、优先级或字段保护；
- 旧 `DatasourceAccessService`、`DatasourcePermissionService`、`PermissionCalculator`、旧 DTO/VO 的权限字段；
- JWT 中的 `roles`、`permissions`、`*` authority；
- 已退役权限缓存或权限计算结果。

新 Resolver 的数据库访问层应只注入 S1 Mapper，代码评审用 import、SQL 表名和调用图证明闭合读取集合。若 S1 关系不存在、状态无效、字段资源为空、快照不匹配或协议不正确，返回 DENY，不返回空白名单后再解释为全库。

### 4.3 同名权限码的隔离

下列字符串当前已在旧 migration、Controller 或前端出现：`datasource:manage`、`audit:view`、`knowledge:manage`、`prompt:manage`、`prompt:approve`、`system:ai-config:view`、`system:ai-config:manage`。它们在 S1 中只作为固定目录的代码文本重新登记，S1 关系使用新的 `iam_s1_function.id`，不按 code 查旧表，不按 role_code、role_id 或历史名称找到旧关联。新的 role/function 关系必须由管理员重新选择和保存。

### 4.4 新 JWT、旧会话和入口隔离

新 JWT 只承载身份和协议，不承载功能权限列表：

```json
{
  "sub": "username",
  "uid": 123,
  "jti": "uuid",
  "iat": 1780000000,
  "exp": 1780086400,
  "authProtocolVersion": "IAM-SIMPLE-1",
  "sessionEpoch": 7
}
```

冻结规则：

1. 新入口只接受 `authProtocolVersion == IAM-SIMPLE-1`、用户已启用、`sessionEpoch` 匹配的 JWT；缺失/未知协议字段直接 401/403。
2. 新 JWT 不放 `roles`、`permissions` 或业务数据范围；每次管理 API、查询 API 和内部调用由 Java 根据当前 S1 事实计算能力/快照。Spring authorities 只可由 S1 Resolver 在请求生命周期内生成。
3. B5 切换维护窗口先停止接收新旧混合入口的请求，排空旧查询任务，增加新会话 epoch 并使历史会话失效；历史令牌因缺少 `authProtocolVersion` 不进入 S1 入口。
4. 新环境的入口路由、认证过滤器、Resolver、缓存命名空间和 Python 内部目标均只指向 S1；旧环境在切换前作为完整环境保留。一个 HTTP 请求只允许命中一套权限实现。
5. B5 失败时关闭 S1 入口并回退整套旧环境/配置；不把 S1 拒绝转换成旧权限重试。回退完成后由环境级验收确认，不能按请求选择。

### 4.5 S1 缓存隔离

新 S1 权限缓存键冻结为：

```text
iam-s1:permission:{protocolVersion}:{userId}:{datasourceId}:{permissionRevision}
```

值为不含明文记录参数的权限快照摘要；绑定参数只在受保护的 Java/Python 执行通道中使用，不进入日志、模型上下文或普通 Redis 展示。权限关系事务提交时先增加 `permissionRevision`，再使对应 Redis 键失效。Redis 不可用时直接查询 S1 数据库并完成同样的拒绝规则；不能因缓存故障返回更宽范围。

旧 Caffeine 权限缓存、旧权限键和历史令牌键在 B6 按清单清理。非权限的账号限频、验证码、通用 AI/Embedding 实例缓存、知识业务资产和已过滤的公开语义缓存不因名称相近而误删，但涉及查询上下文的缓存必须在 B3 复查权限 revision。

## 5. 首个新系统管理员初始化

### 5.1 一次性流程

1. 首个管理员不通过 HTTP/UI 或旧管理权限初始化。部署负责人在维护窗口以服务端启动参数 `IAM_S1_BOOTSTRAP_ENABLED=true` 和 `IAM_S1_BOOTSTRAP_USER_ID=<已保留账号ID>` 启动一次性 bootstrap 模式；该模式下普通业务 HTTP 入口不对外提供服务。
2. Java 启动流程检查持久化 bootstrap 状态，并仅按显式用户 ID 查询 `sys_user`；目标必须存在、未删除且已启用。在单一事务中创建 `iam_s1_user_role` 到受保护 S1 系统管理员角色的绑定；不读取旧 `ADMIN`、旧 `*`、旧角色 ID 或历史管理员名称。
3. 同一事务写入 `iam_s1_audit_event`：部署来源、宿主机/发布标识、目标账号 ID、S1 角色 ID、时间、执行 ID、结果和失败原因。不得记录密码、JWT、密钥或环境变量原值。
4. bootstrap 状态和受保护角色绑定使用数据库唯一约束与事务锁保证只有一个首次成功事实；幂等键为 `IAM-SIMPLE-1 + targetUserId`。相同目标重复执行返回已完成；不同目标和并发第二次执行拒绝；失败不产生半条可用绑定。
5. 成功后持久化 `COMPLETED`、完成时间和实施版本。部署负责人删除两个启动参数并正常重启；后续即使再次提供参数，服务端也因持久化完成状态拒绝再初始化。不存在可长期打开的 bootstrap HTTP 端点。

### 5.2 切换前验证

首个新管理员必须完成以下真实链路后才能 B5：

- 用新 JWT 登录并通过 `/api/auth/me`；
- 查看 S1 固定 54 项功能目录；
- 创建一个普通新角色并分配固定功能；
- 为该角色绑定负责数据源；
- 配置一份明确的数据授权、表列白名单和记录条件；
- 看到统一实际权限预览；
- 用授权范围内查询验证结果、SQL/导出能力和字段保护；
- 用非授权数据源、空字段、未知协议和无范围请求验证拒绝；
- 确认持久化 bootstrap 状态为 `COMPLETED`，正常重启时不再进入 bootstrap 模式，重复初始化和第二目标账号均被拒绝。

未完成上述验证，不得执行 B5；本次 B0 不选择真实账号、不调用初始化接口。

## 6. Java/Python IAM-SIMPLE-1 新契约

### 6.1 Java 产生的请求级权限快照

Java 是快照唯一产生者。推荐传输结构如下，字段名在 B3 合同实现时保持稳定：

```json
{
  "protocolVersion": "IAM-SIMPLE-1",
  "userId": 123,
  "datasourceId": 10,
  "activeMetadataSnapshotId": 88,
  "permissionRevision": 42,
  "calculatedAt": "2026-09-16T10:00:00+08:00",
  "nextEffectiveAt": "2026-09-16T18:00:00+08:00",
  "resources": [
    {
      "tableName": "orders",
      "columns": [
        {"name": "order_id", "visibility": "VISIBLE"},
        {"name": "amount", "visibility": "VISIBLE"},
        {"name": "phone", "visibility": "MASKED", "maskStrategy": "PHONE"}
      ],
      "grantSources": [
        {
          "grantId": 9001,
          "subjectType": "DEPARTMENT",
          "subjectId": 7,
          "rowCondition": {
            "match": "ALL",
            "predicates": [
              {"column": "region", "operator": "EQ", "valueType": "STRING", "parameter": "p1"}
            ]
          },
          "parameterBindings": {"p1": {"type": "STRING", "value": "华东"}}
        }
      ]
    }
  ],
  "capabilities": {
    "query": true,
    "viewSql": false,
    "export": false
  }
}
```

契约规则：

- `protocolVersion`、`userId`、`datasourceId`、`activeMetadataSnapshotId`、`permissionRevision`、`calculatedAt` 必填；`nextEffectiveAt` 字段必须存在但允许为 `null`；用户/数据源必须和 Java 当前请求一致。
- `resources` 必须非空；每张表的 `columns` 必须非空且来自当前已发布快照。没有明确列不解释为全部列。
- `grantSources` 保留每份授权与主体的关联，不能把字段集合和记录条件拆开后重新相乘；多表查询逐表保存条件。
- `rowCondition` 只能使用服务端固定的结构化节点、字段白名单、类型和操作符；`parameterBindings` 由 Java 解析/绑定，原值不出现在问题、Schema、Prompt、SQL 解释、错误或审计展示中。
- `HIDDEN` 字段不出现在 `resources.columns` 的模型可见部分；`MASKED` 只能直接投影，不能用于过滤、关联、排序、分组或函数运算。
- `capabilities.viewSql/export` 只是结果能力，不产生数据授权；`export=true` 仍要再次检查当前结果和字段保护。
- `nextEffectiveAt` 为最近一条授权、禁止或字段保护的未来生效/失效边界。Java 已扫描所有相关规则并确认没有未来边界时可为 `null`；计算失败时不签发快照而直接 DENY。缓存 TTL 取“距 `nextEffectiveAt` 的剩余时间”与配置最大 TTL 中的较小值；为 `null` 时仍使用最大 TTL 和 revision 失效，不写伪造的无限未来时间。

### 6.2 Java → Python 分层契约

| 层/动作 | 必须携带或执行 | 明确拒绝规则 |
|---|---|---|
| Agent `/internal/query/execute` | `protocolVersion`、用户/数据源、快照、revision、完整 `resources`、结构化 `rowCondition`、字段保护、结果能力、会话上下文和已过滤知识；Python 只读快照。 | 协议缺失/未知、用户或源不一致、资源为空、快照无效、绑定参数缺失、表列来源不完整，拒绝 Agent。 |
| RAG retrieve/fallback/few-shot | 只接收已按当前 `resources` 过滤的表列和文档 chunk；保留 `datasourceId/snapshotId/doc/version/entity/source`；S1 row 参数不进入模型。 | 未绑定表列、快照不同、治理不准入、字段不在白名单、无法确认来源，拒绝进入模型；缓存命中后仍要按 revision 重检。 |
| SQL AST validate/rewrite | AST 检查所有表、投影、条件、JOIN、排序、分组、子查询和派生别名；Java/Python 使用服务端参数绑定的 row condition；只读 SQL、LIMIT、来源追踪。 | 任一未授权表/列、空表列白名单、隐藏/脱敏字段出现在禁止位置、无法追踪来源、记录条件无法绑定，拒绝执行。 |
| SQL execute | 只接受已通过 AST 的内部执行对象和绑定参数；连接账号只读；执行结果回 Java。 | 不能直接根据前端 SQL 或只带 `maskColumns` 的请求执行；没有完整 S1 执行对象必须拒绝。 |
| Java 保存/返回 | 任务落库前保存使用资源、revision、快照、保护状态和能力摘要；返回/历史恢复/SQL 查看/导出前再次计算当前 S1 快照；Java 最终脱敏。 | 权限收紧、过期、缓存故障无法确认一致性、本人归属不成立，停止返回并提示重新查询；不把新条件套到旧聚合结果。 |

### 6.3 各层职责冻结

- Java：固定目录、角色依赖、用户/部门/角色事实、角色负责源、数据授权、字段保护、审批、统一 Resolver/预览、revision、会话失效、审计和最终响应保护。
- Python：只消费 S1 请求级快照；执行权限感知 RAG、Schema/来源过滤、SQL AST、参数化记录条件、只读沙箱和结果元信息。Python 不保存长期权限事实。
- Vue：按 Java 返回的能力摘要显示/隐藏路由、Tab 和按钮；直接调用 API 仍必须通过 Java Controller/Service 强制校验。

## 7. B6 删除清单

删除必须在 B5 正式切换、真实复验通过且输出本表复核记录后执行。每行均包含替代物、时机、验证、历史和误删风险。

| B6 对象 | B1-B4 替代物 | B6 删除时机 | 删除前验证方式 | 历史审计 | 误删风险与处理 |
|---|---|---|---|---|---|
| `sys_permission`、`sys_role` 表、字段、索引、旧权限专属记录、旧 `role_code/permission_code` | `iam_s1_function`、`iam_s1_role`、`iam_s1_role_function` | B1 新目录/角色验收且 B5 切换后，删除表及索引 | 扫描所有 Java SQL/import、Controller guard、前端 code；确认无运行引用 | 保留 S1 审计和旧权限变更历史 | 不删除 `sys_user`；逐表核对只删除权限表，不动账号组织业务数据 |
| `sys_user_role`、`sys_role_permission` 表、字段、唯一索引/普通索引及关联数据 | `iam_s1_user_role`、`iam_s1_role_function`、`iam_s1_role_datasource` | B1 新关系验证、B5 切换后，删除表及索引 | 证明每个正式用户的 S1 绑定和管理员绑定存在；证明 Resolver 无旧表查询 | 旧关系行不留在可运行授权数据库中；删除前仅保留不可参与计算的备份/计数校验摘要，权限变更历史继续使用只读审计表 | 不能删除 `sys_user` 或 `sys_department`；先导出对象计数和备份校验 |
| `datasource_access` 表、`subject_type/subject_id/can_query/can_export/can_view_sql/access_effect/expires_at`、旧关联数据和索引 | `iam_s1_data_grant*`、`iam_s1_role_datasource`、S1 capabilities | B2/B3 新授权和结果链路验收、B5 后，删除表及索引 | 每个数据源的 S1 grant/deny、表列和能力通过矩阵；无旧 Mapper/SQL 访问 | 旧授权变更历史保留只读；活动授权不继续运行 | `datasource`、`datasource_secret`、元数据和知识资产不删除 |
| `datasource_access_policy` 表、旧字符串行条件、`access_type/mask_strategy/priority/valid_* /time_schedule`、索引和活动策略 | `iam_s1_data_grant_column`、`iam_s1_row_condition`、`iam_s1_field_protection` | B2/B3 AST/RAG/结果保护验收、B5 后，删除表及索引 | 结构化条件解析、字段保护、拒绝优先、到期和多表测试全通过 | 旧策略变更事件保留只读历史 | 不删除 `db_table_meta/db_column_meta`；不清理 Milvus 活动向量，RAG 生命周期单独验证 |
| `permission_change_log` 的旧写入路径、旧 Mapper/Entity | `iam_s1_audit_event` 与现有业务操作审计接口 | B2 新审计写入验证、B5 后 | 新授权/撤销/审批/字段保护操作均有 S1 audit event 且可追溯 | **保留该表及历史行，只读** | 不把历史行当 S1 事实；表保留，旧写入代码删除 |
| `access_approval_request` 活动状态、旧审批临时策略生成路径 | `iam_s1_access_request/approval`、S1 临时 grant | B2/B4 新申请审批和到期测试、B5 前先终止旧未完成申请，B5 后删除旧生成路径 | 本人申请、负责源队列、非本人审批、子集、期限、禁止字段和到期均通过；旧 `PENDING` 记录已统一终止并记录原因 | **保留历史申请/审批记录，只读** | 不删除 `sys_user`、数据源、表列或审计；旧表仅作历史展示，不再产生临时授权 |
| `DatasourceAccessService`/`DatasourceAccessServiceImpl`、`DatasourcePermissionService`/`Impl`、旧 `PermissionCalculator`/`Impl`、旧 `DataMaskingService` 中权限读取分支 | `IamS1AuthorizationResolver`、`IamS1SnapshotService`、Java 最终保护服务 | B1-B3 新服务通过、B5 切换后 | `rg` 证明无旧表、旧码、旧 Resolver 运行引用；全量直接 API 回归 | 审计事实保留 | `DataMaskingService` 的通用掩码算法可由新服务复用，但旧输入接口必须删除，不能整类误删业务安全能力 |
| `DatasourceAccessMapper`、`DatasourceAccessPolicyMapper`、`PermissionChangeLogMapper`、`AccessApprovalRequestMapper` 旧 SQL/`BaseMapper` | S1 专用 Mapper，只访问 `iam_s1_*` | B1-B3 新 Mapper 通过、B5 后 | SQL 表名白名单扫描；旧 Mapper 无生产 bean 引用 | 历史只读读取使用独立历史查询，不参与 Resolver | 不删除通用 `DatasourceMapper`、`MetadataMapper`、`QueryTaskMapper` |
| 旧 permission Entity/DTO/VO：`DatasourceAccess`、`DatasourceAccessPolicy`、`DatasourcePermissionGrantDTO`、`AccessPolicy*DTO`、`PermissionContextVO` 等旧授权形状 | S1 DTO/VO 与结构化快照 | B3 契约切换后、B5 后 | Java 编译依赖扫描、Python schema 对照、请求抓包确认只含 S1 | 历史 JSON 不改写；按只读历史解析 | `QueryTask.masked_fields` 等业务字段保留，不因名称含 permission 误删 |
| `DatasourcePermissionController`、`AccessPolicyController`、`AccessApprovalController` 旧 API 和旧方法表达式 | B4 新 IAM Controller；自助申请与管理员队列分离 | B4 新路由/API/按钮和直接调用验收、B5 后 | 旧路径返回 404/明确退役；新路径 401/403/200 组合测试 | 历史 API 审计保留 | 不删除业务查询/元数据/知识 Controller；只按路由和 bean 引用清理 |
| 所有 Controller 的旧 `@PreAuthorize` 字符串、旧权限别名、`hasAnyAuthority('*')` 的业务授权绕过 | S1 `AuthorizationDecision` + 资源范围检查 | B4 所有 54 码接入、B5 后 | 直接 API 对列表/详情/批量/统计/导出逐项做拒绝测试；静态扫描无旧表达式 | 操作审计保留 | `*` 仅作为静态历史证据，不保留为新运行入口 |
| `JwtTokenProvider` 旧 claim `roles/permissions/tokenVersion`、`UserDetailsServiceImpl` 旧权限加载、`JwtAuthenticationFilter` 旧解析路径 | S1 身份 JWT：`authProtocolVersion`、`sessionEpoch`；S1 Resolver 动态生成能力 | B5 入口切换、旧会话失效确认后 | 旧 JWT 缺协议字段被拒绝；新 JWT 不含权限数组；账号启停/密码变更会使 S1 会话失效 | 认证审计保留 | `sys_user.password_hash`、登录失败/验证码机制保留；只删旧 claim 解析和旧权限加载 |
| `jwt:blacklist:{jti}`、`user:token-version:{userId}` 旧会话键；权限计算的 Caffeine `userId:datasourceId`、`CacheConfig` Caffeine 权限配置 | `iam-s1:session:*`、`iam-s1:permission:*:revision` Redis 键 | B5 作废旧会话、S1 Redis 回源验证后 | Redis 键前缀扫描、失效/回源/故障演练；无 Caffeine 权限实例 | 会话失效事件保留 | 保留账号安全的限频/验证码键和非权限业务缓存；逐前缀删除，禁止全库清理 |
| `frontend/src/router/guards.ts` 的旧 adminPermissions、`auth.ts` 旧 permission 数组和 `hasPermission` | Java S1 capability snapshot 仅用于 UI 可见性；路由后端仍强制 | B4 新 UI 守卫和直接 API 验收、B5 后 | 无旧权限数组作为前端授权源；刷新后从 Java 获取 S1 capability | 登录/导航审计保留 | 不删除登录 token、个人资料、query 会话状态；仅清理权限入口逻辑 |
| `OrganizationView` 的“权限项”任意 CRUD、`RoleList` 的旧权限树/权限 ID 配置、`frontend/src/api/admin/user.ts` 旧 permission CRUD | 固定 54 项中文只读目录；角色使用固定矩阵 | B4 中文角色配置通过、B5 后 | 不能创建未知码；54 码目录数为 54；角色保存由后端校验 | 操作日志保留 | `RoleList` 的角色/成员业务入口保留并接入 S1；只删旧权限 CRUD 子树 |
| `frontend/src/api/admin/permission.ts` 旧 access/policy/approval API、`AccessControl.vue` 旧表列策略表单和分散 MASK 写入口 | S1 授权、字段保护、实际权限、申请/审批页面 | B4 页面与新 API 成对验收、B5 后 | UI 与 API route graph 对照；按钮直连不存在的旧 API 时构建失败 | 历史页面访问记录保留 | 组织、数据源、元数据页面保留；不删除通用 `ResourceScopeSelector`，只移除旧授权消费 |
| 旧 Java/Python/前端测试：`UserDetailsServiceImplTest`、`WildcardAuthorizationAnnotationTest`、`DatasourceAccessServiceImplTest`、`PermissionCalculatorImplTest`、`AccessApprovalServiceImplTest`、`AuthServiceImplTest`、`PythonAgentClientImplTest`、`test_sql_nodes.py`、`test_sse_polling.py`、`test_sandbox_regression.py` 以及旧 guard/query 断言 | S1 默认拒绝、隔离、快照、资源范围、结果保护、初始化、切换和清理测试 | B1-B5 新测试全部通过后，删除旧专属用例；业务安全通用测试迁移后再删 | 测试清单逐项标注迁移/删除；B6 后 `rg` 无旧运行引用 | 测试文件不属于业务审计；历史测试结果保留 Git history | 不删除通用 AST、RAG、只读 SQL、密码和账号测试；先迁移有效安全断言 |

## 8. 明确保留清单

以下对象在 B6 保留，不作为旧权限清理目标：

- `sys_user` 的账号、密码哈希、真实姓名、联系方式、启停状态、登录时间和账号安全状态；
- `sys_department` 的真实部门树、部门编码、主部门标识和组织审计；
- `datasource`、`datasource_secret`、连接健康记录及其加密密钥生命周期；
- 元数据采集、已发布快照、表列信息、治理问题、字段标签、可信度、术语、知识文档、知识版本、chunk 和 Milvus 业务索引；
- `conversation`、`conversation_message`、`conversation_context_summary`、`query_task`、查询结果、推荐问题和查询血缘；
- 查询审计、快照审计、操作日志、通知、元数据变更事件和权限历史审计；
- 所有已经执行的 Flyway migration 文件和 Flyway schema history；已执行 migration 不改写、不删除；
- Java/Python 的只读 SQL、AST 安全、连接池生命周期、RAG 向量版本切换和内部服务令牌基础设施；其权限输入按 B3 替换。

“保留”不等于继续授予访问权：账号和组织保留，但新角色绑定、负责源和数据授权必须由管理员按 S1 重新建立；历史审计保留，但不进入新 Resolver。

## 9. 验收矩阵与 B1 准入

### 9.1 必须通过的验收项

| 编号 | 场景 | 通过条件 | 责任阶段 |
|---|---|---|---|
| A-01 | 旧用户只有同名旧权限码，没有 S1 新绑定 | 登录可保留；所有 S1 管理/查询/后台请求拒绝；同名字符串不产生能力 | B1/B5 |
| A-02 | 历史角色关系、历史令牌、历史缓存 | 任一历史 `sys_*` 关系、历史令牌 claim、历史缓存命中都不能产生 S1 功能或数据权 | B1/B5/B6 |
| A-03 | 无角色、无数据授权、字段为空、协议缺失/未知 | 默认拒绝；不转换为全库或全字段 | B1-B3 |
| A-04 | 系统管理员访问业务数据 | 拥有全部后台功能和负责源，但没有显式数据 grant 仍不能问数/导出业务数据 | B1-B3 |
| A-05 | 后台功能和负责源 | 只有同一个启用角色绑定同时拥有功能和目标源负责范围时通过；两个角色不能交叉相乘 | B1/B4 |
| A-06 | 详情、批量、统计、导出 | 与列表使用同一资源范围；审计详情/统计、用户导出、问题批量、血缘批量、readiness batch 均单独直接 API 验证 | B2-B4 |
| A-07 | 首个新系统管理员 | 显式选择保留启用账号；初始化幂等、审计完整、验证登录/目录/角色/负责源/数据授权；一次性入口关闭 | B5 |
| A-08 | B5 失败 | 只能关闭 S1 入口并按维护窗口回退整套环境；单请求不能调用已退役解析服务 | B5 |
| A-09 | B6 清理 | `rg`、依赖图、运行启动和直接 API 证明不存在旧权限运行引用；旧表/类/端点/缓存键按清单处理 | B6 |
| A-10 | 非权限业务数据 | 账号、密码、真实组织、数据源、元数据、知识、会话、业务结果、查询审计和 Flyway 历史仍可读且行数/版本核对一致 | B5/B6 |
| A-11 | 查询安全链 | 表列资源、结构化记录条件、RAG、Schema Linking、few-shot、历史/摘要、SQL AST、沙箱、最终脱敏全部使用同一 revision；原值和绑定参数不进模型 | B2/B3 |
| A-12 | 字段保护 | HIDDEN 不入 Schema/RAG/SQL；MASKED 不得出现在 WHERE/JOIN/ORDER/GROUP/函数；表格、图表、SQL、错误、审计和导出均不泄露原值 | B2/B3 |
| A-13 | 审批 | 本人申请可看本人；管理员只看负责源；不能审批本人；批准范围是申请子集且有期限，禁止/阻断/废弃资源不能开通 | B2/B4 |
| A-14 | 缓存故障与权限收紧 | Redis 故障回源或拒绝；执行中撤权/到期/无法确认一致性时停止并要求重新查询 | B2/B3 |

### 9.2 B1 准入条件

B1 必须同时满足以下条件，否则停止在 B0：

1. 本文已评审，54 个码矩阵、211 个 Java API、26 个后台路由、49 个 Tab、277 个按钮声明和 17 项 B6 删除对象被逐项确认。
2. §4 独立新表方案、S1 JWT、Redis 命名空间、缺失/未知协议拒绝和环境级入口隔离没有替代方案并存。
3. §5 首个管理员流程和 B5 验证条件获得确认；本次不执行初始化。
4. §6 的快照字段、结构化记录条件、模型可见边界、Java 最终保护和直接 API 拒绝规则成为 B1-B4 的共同合同。
5. §7 删除/保留对象明确，尤其是旧角色关系、数据授权、权限计算、JWT、缓存、前端入口、历史审计和非权限业务数据。
6. 下列当前阻塞项在 B1 立项时有明确关闭任务；B1 不得以旧实现继续运行来绕过：
   - 独立 S1 表/Mapper/Resolver 尚未实现；
   - 当前 Caffeine 权限缓存和旧表权限计算尚未移除；
   - 当前角色未绑定后台负责源；
   - 当前 Python Agent/RAG/SQL 执行契约缺少 S1 版本、revision、完整资源和结构化条件；
   - 结果详情、导出、SSE、审计详情/统计、审批和字段保护存在直接 API 范围缺口；
   - 首个 S1 系统管理员尚未建立和验证；
   - 当前旧前端守卫、权限树 CRUD 和授权入口尚未改为固定 54 项中文目录。

## 10. B0 静态验证记录

本次只做静态验证，不运行代码测试：

- `git status --short --branch`：开始前工作树干净，分支为 `codex/permission-system-guide`，领先远端 1 个提交；未覆盖用户修改。
- 主设计版本检查：`DataOcean-完整权限体系设计.md:3` 为简明版 1.4，执行协议为 IAM-SIMPLE-1，继续执行。
- 目标码唯一性：从主设计第 4 节提取 54 个 code，唯一值 54，重复 0，遗漏 0。
- Java Controller API：37 个 Controller、211 个方法级 API 注解；完整清单见 §2.1。
- 前端导航：7 个一级域、20 个正式工作区、26 个后台路由声明；完整来源见 E-NAV。
- Tab：43 个后台 `el-tab-pane` 运行时功能项 + 2 个 AI 自定义 Tab + 4 个问数结果 Tab = 49。
- 按钮：后台视图 237 + 后台 Shell 6 + 问数视图 34 = 277 个按钮标签/组件声明；不是把 277 个都误判为独立权限码消费。
- API/路由/Tab/按钮与 54 码矩阵逐项有“有/部分/无”消费结论；`audit:export` 明确无真实消费，没有编造接口。
- B6 删除清单和保留清单均存在，并为对象标记替代物、时机、验证、历史和误删风险。
- 全文约束词扫描：对本 B0 文档执行规定关键词残留扫描；命中只允许出现在 §0.1 禁止规则或 §7 B6 删除清单。设计基准、状态文档和执行队列中的相关文字属于已存在的约束说明，不作为本 B0 文档的运行实现证据。
- `git diff --check`：本文件及同步文档完成后执行；必须无 whitespace error。
- 未运行 `mvn test`、`uv run pytest`、`npm run build`，未启动服务、Docker 或数据库。
- 评审复核：修正了审计证据标签、B6 旧关系“删除与保留”矛盾、导出能力边界、长期授权 `nextEffectiveAt` 空值语义和无 HTTP 后门的启动式管理员 bootstrap；复核后 B0-1.1 通过。

## 11. 停止点

B0 到此结束并已评审通过。本文未进入 B1，本轮不实施数据库、新权限代码、前端改造、切换、初始化或 B6 清理；后续开发从 B1 独立新表/Mapper/Resolver 任务开始，并按 §9.2 逐项关闭实施阻塞。

---

## 附录：批次 4 冻结的消费结论（2026-09-20）

**`governance:rule:manage` 的两种范围共存，不是系统管理员专属功能码。**

功能码 18 `governance:rule:manage` 同时服务两类写入：

- **全局质量规则启停**（`metadata_quality_rule`，无 datasourceId）：启停影响所有数据源，因此**只允许受保护 S1 系统管理员**执行。非系统管理员即使在某个负责源上持有该功能码，也不能修改全局规则。
- **表/列治理状态写入**（有 `snapshotId` 归属）：由“功能 + 目标负责源在同一启用绑定上同时成立”放行，**负责源管理员可正常维护**。

**因此不得把整个 `governance:rule:manage` 改成系统管理员专属**，否则会错误阻止负责源管理员维护表列治理状态。差异在 `QualityRuleServiceImpl.updateEnabled` 内强制，不在注解层表达。

**质量问题列表的范围语义**：`GET /api/admin/quality-issues` 未指定快照时必须按 `governance:issue:view` 的负责源在 SQL 层下推；空负责源返回空页，不退化成全局查询。`QualityIssueService.listIssuesInDatasources` 用集合表达范围——**空集合表示空页，不用 null 表示“有时全局、有时空范围”**。

**质量问题资源归属**：新增 `IamS1ResourceType.GOVERNANCE_ISSUE`。归属以**快照的真实 datasourceId** 为准，并校验问题行上的 `datasource_id` 与之一致；不一致返回 409 而非择一使用。

**审核记录归属**：`GET /api/admin/snapshots/{snapshotId}/review-records` 使用 `metadata:release:view`，**不因持有治理查看权而自动获得**快照版本审核记录。
