# Tasks: 血缘与审计模块

**Input**: Design documents from `specs/011-lineage-audit/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V12__create_audit_tables.sql`，包含 query_audit_log、query_lineage_table、query_lineage_column、llm_usage_log 四张表的 DDL，query_audit_log 添加索引（user_id、datasource_id、created_at、is_slow）
- [ ] T002 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V13__create_quota_tables.sql`，包含 quota_policy 表的 DDL
- [ ] T003 创建 Java 包结构 `backend/src/main/java/com/dataocean/module/audit/`，包含 controller/service/mapper/entity/dto 子包

## Phase 2: Foundational — 审计日志记录

- [ ] T004 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/audit/entity/QueryAuditLog.java`，包含 id、query_task_id、user_id、datasource_id、question、sql_text、used_tables（JSON 字符串）、used_fields（JSON 字符串）、execution_time_ms、row_count、is_success、error_message、is_slow、user_feedback、created_at
- [ ] T005 [P] 实现 Mapper 接口 `backend/src/main/java/com/dataocean/module/audit/mapper/QueryAuditLogMapper.java`，继承 BaseMapper，添加自定义方法：selectByCondition（多维度筛选）、countByDatasource（按数据源统计）、selectSlowQueries（慢查询列表）
- [ ] T006 实现 `backend/src/main/java/com/dataocean/module/audit/service/AuditLogService.java`：包含 recordAudit（异步写入审计记录，使用 @Async 注解）方法——接收查询结果后构建 QueryAuditLog，自动标记 is_slow（execution_time_ms > 配置阈值，默认 5000ms），写入数据库
- [ ] T007 在 application.yml 中添加审计配置项 `dataocean.audit.slow-query-threshold-ms: 5000` 和 `dataocean.audit.retention-days: 180`

## Phase 3: User Story 1 (P1) — 完整审计记录

**Goal**: 系统记录完整查询审计日志
**Independent Test**: 查询完成后，审计日志中能找到该条记录，包含所有必要字段

- [ ] T008 [US1] 在 AuditLogService 中添加 updateFeedback 方法：用户对查询结果点赞/踩后，更新对应 query_audit_log 的 user_feedback 字段
- [ ] T009 [US1] 实现审计日志查询 DTO `backend/src/main/java/com/dataocean/module/audit/dto/AuditLogQueryRequest.java`：包含 userId、datasourceId、startTime、endTime、isSuccess、isSlow、keyword（模糊搜索 question）、pageNo、pageSize
- [ ] T010 [US1] 实现 `backend/src/main/java/com/dataocean/module/audit/controller/AuditLogController.java`：GET /api/admin/audit-logs（多维度筛选分页查询）、GET /api/admin/audit-logs/{id}（详情）、GET /api/admin/audit-logs/export（导出 CSV，按查询条件筛选）

## Phase 4: User Story 2 (P2) — 血缘解析

**Goal**: 系统解析 SQL 生成血缘关系
**Independent Test**: 一条 JOIN 查询执行后，血缘图中能看到两张表的关联关系

- [ ] T011 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/audit/entity/QueryLineageTable.java`，包含 id、query_task_id、source_table、target_name、relation_type（FROM/JOIN/SUBQUERY）、created_at
- [ ] T012 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/audit/entity/QueryLineageColumn.java`，包含 id、query_task_id、source_table、source_column、expression、alias_name、created_at
- [ ] T013 [US2] 实现 `backend/src/main/java/com/dataocean/module/audit/service/LineageService.java`：包含 saveLineage 方法（接收 Python 返回的 used_tables 和 used_columns 解析结果，批量写入 query_lineage_table 和 query_lineage_column）、queryTableLineage（按表名查询所有引用该表的历史查询）、queryColumnLineage（按字段查询所有引用该字段的历史查询和 skills.md 片段）
- [ ] T014 [US2] 实现 `backend/src/main/java/com/dataocean/module/audit/controller/LineageController.java`：GET /api/lineage/table/{tableName}（表级血缘查询）、GET /api/lineage/column/{tableName}/{columnName}（字段级血缘查询）、GET /api/lineage/impact/{tableName}/{columnName}（变更影响分析——返回依赖该字段的查询数量和 skills.md 片段）

## Phase 5: User Story 3 (P2) — LLM 成本与配额

**Goal**: 管理 LLM 调用成本和查询配额

- [ ] T015 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/audit/entity/LlmUsageLog.java`，包含 id、query_task_id、provider（QWEN）、model、prompt_tokens、completion_tokens、total_tokens、cost_amount（单位：元）、created_at
- [ ] T016 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/audit/entity/QuotaPolicy.java`，包含 id、subject_type（USER/DEPARTMENT/DATASOURCE）、subject_id、daily_query_limit、monthly_cost_limit、enabled、created_at、updated_at
- [ ] T017 实现 `backend/src/main/java/com/dataocean/module/audit/service/LlmUsageService.java`：包含 recordUsage（记录单次 LLM 调用）、getDailyUsage（按用户/数据源查询当日使用量）、getMonthlyUsage（月度统计）
- [ ] T018 实现 `backend/src/main/java/com/dataocean/module/audit/service/QuotaService.java`：包含 checkQuota（查询前检查用户是否超出配额，返回 boolean + 剩余额度）、createPolicy（创建配额策略）、updatePolicy（更新策略）、listPolicies（列表查询）
- [ ] T019 实现 `backend/src/main/java/com/dataocean/module/audit/controller/QuotaController.java`：GET /api/quotas（配额策略列表）、POST /api/quotas（创建）、PUT /api/quotas/{id}（更新）、GET /api/quotas/check/{userId}（检查用户配额）

## Phase 6: 管理功能

- [ ] T020 实现慢查询列表接口：在 AuditLogController 中添加 GET /api/admin/audit-logs/slow-queries（筛选 is_slow=true 的记录，按 execution_time_ms 降序，分页返回）
- [ ] T021 实现审计统计接口：在 AuditLogController 中添加 GET /api/admin/audit-logs/stats（接收 datasourceId 和时间范围，返回查询总数、成功率、平均耗时、慢查询占比）
- [ ] T022 实现模板提升接口：在 AuditLogController 中添加 POST /api/admin/audit-logs/{id}/promote-template（管理员将高频成功查询提升为查询模板，记录 question + sql_text 到模板表）
- [ ] T023 实现数据清理定时任务 `backend/src/main/java/com/dataocean/module/audit/service/AuditCleanupJob.java`：@Scheduled(cron="0 0 2 * * ?") 每天凌晨 2 点执行，删除 created_at 早于 retention-days 天的 query_audit_log、query_lineage_table、query_lineage_column 记录，按批次删除（每批 1000 条）避免锁表

## Phase 7: Polish & Cross-Cutting

- [ ] T024 实现所有 DTO/VO 类 `backend/src/main/java/com/dataocean/module/audit/dto/`：AuditLogVO、LineageVO（包含表级和字段级血缘图结构）、SlowQueryVO、PromoteTemplateRequest
- [ ] T025 在 AuditLogService.recordAudit 中确保异步写入失败时不影响主流程：添加 @Async 异常处理，失败时仅记录 error 日志
- [ ] T026 为 query_audit_log 表添加按月分表策略注释（MyBatis-Plus 分表插件配置），当单表超过 500 万行时启用

## Phase 9: Alerts & Cost Dashboard

- [ ] T031 在 Java 后端创建 alert_rule 表（id, metric, threshold, operator, notification_type, enabled）和 AlertService：支持配置告警规则（如错误率>20%、慢查询数>10/小时）
- [ ] T032 创建 AlertCheckScheduler（@Scheduled 每 10 分钟），检查各指标是否触发告警规则，触发时写入 sys_notification
- [ ] T033 [Frontend] 在审计页面添加"LLM 成本"标签页：按天/周/月展示 Token 消耗、API 调用次数、预估费用（从 llm_usage_log 聚合）
- [ ] T034 [Frontend] 在系统设置中添加"告警规则"配置页面：管理告警规则的增删改查和启用/禁用

## Dependencies

```
T001~T002 → T003 → T004~T005 → T006~T010
T003 → T011~T014 (血缘依赖包结构)
T003 → T015~T019 (成本配额依赖包结构)
T006 → T008 (反馈更新依赖审计写入)
T006 + T013 → T020~T023 (管理功能依赖基础服务)
T018 → 008 模块查询前调用 checkQuota
```

## Implementation Strategy

MVP-first approach:
1. 先完成审计日志记录（Phase 2-3），确保每次查询都有完整记录——这是 008 Agent 模块的后置依赖
2. 血缘解析（Phase 4）接收 Python sqlglot 的解析结果直接存储，不重复解析
3. LLM 成本和配额（Phase 5）可与审计并行开发
4. 管理功能（Phase 6）最后完善，提供运维可观测性
5. 审计写入必须异步化，绝不能阻塞查询主流程

## Phase 8: Frontend Pages

- [ ] T027 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/audit.ts`：审计日志查询/导出、慢查询列表、血缘查询
- [ ] T028 [Frontend] 创建审计日志页面 `frontend/src/views/admin/audit/AuditLogList.vue`：按用户/数据源/时间/状态筛选、分页、导出 CSV
- [ ] T029 [Frontend] 创建慢查询页面 `frontend/src/views/admin/audit/SlowQueryList.vue`：执行超过 5s 的查询列表、SQL 详情、优化建议入口
- [ ] T030 [Frontend] 创建血缘查看页面 `frontend/src/views/admin/audit/LineageViewer.vue`：表级/字段级依赖关系列表、受影响查询数
