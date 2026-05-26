# Tasks: 血缘与审计模块

**Input**: Design documents from `specs/011-lineage-audit/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [X] T001 创建 Flyway 迁移脚本 `V20__create_audit_tables.sql`，包含 query_audit_log、query_lineage_table、query_lineage_column、llm_usage_log 四张表的 DDL
- [X] T002 创建 Flyway 迁移脚本 `V21__create_quota_tables.sql`，包含 quota_policy、alert_rule 表的 DDL
- [X] T003 创建 Java 包结构 `backend/src/main/java/com/dataocean/module/audit/`，包含 controller/service/mapper/entity/dto 子包

## Phase 2: Foundational — 审计日志记录

- [X] T004 [P] 实现实体类 `QueryAuditLog.java`
- [X] T005 [P] 实现 Mapper 接口 `QueryAuditLogMapper.java`
- [X] T006 实现 `AuditLogService.java`：包含 recordAudit（@Async 异步写入）、自动标记 is_slow
- [X] T007 在 application.yml 中添加审计配置项

## Phase 3: User Story 1 (P1) — 完整审计记录

- [X] T008 [US1] 在 AuditLogService 中添加 updateFeedback 方法
- [X] T009 [US1] 实现审计日志查询 DTO `AuditLogQueryDTO.java`
- [X] T010 [US1] 实现 `AuditLogController.java`：GET 分页查询、GET 详情、GET 慢查询、GET 统计、POST 模板提升

## Phase 4: User Story 2 (P2) — 血缘解析

- [X] T011 [P] [US2] 实现实体类 `QueryLineageTable.java`
- [X] T012 [P] [US2] 实现实体类 `QueryLineageColumn.java`
- [X] T013 [US2] 实现 `LineageService.java`：saveLineage、queryTableLineage、queryColumnLineage
- [X] T014 [US2] 实现 `LineageController.java`：GET 表级血缘、GET 字段级血缘、GET 变更影响分析

## Phase 5: User Story 3 (P2) — LLM 成本与配额

- [X] T015 [P] 实现实体类 `LlmUsageLog.java`
- [X] T016 [P] 实现实体类 `QuotaPolicy.java`
- [X] T017 实现 `LlmUsageService.java`：recordUsage、getDailyQueryCount、getUsageStats
- [X] T018 实现 `QuotaService.java`：checkQuota、createPolicy、updatePolicy、listPolicies
- [X] T019 实现 `QuotaController.java`：GET 列表、POST 创建、PUT 更新、GET 检查配额、GET LLM 统计

## Phase 6: 管理功能

- [X] T020 实现慢查询列表接口：GET /api/admin/audit-logs/slow-queries
- [X] T021 实现审计统计接口：GET /api/admin/audit-logs/stats
- [X] T022 实现模板提升接口：POST /api/admin/audit-logs/{id}/promote-template
- [X] T023 实现数据清理定时任务 `AuditCleanupJob.java`：@Scheduled 每天凌晨 2 点按批次删除过期数据

## Phase 7: Polish & Cross-Cutting

- [X] T024 实现所有 DTO/VO 类：AuditLogVO、AuditStatsVO、LineageTableVO、LineageColumnVO、ImpactAnalysisVO、QuotaCheckVO、LlmUsageStatsVO、QuotaPolicyDTO、AlertRuleDTO
- [X] T025 在 AuditLogService.recordAudit 中确保异步写入失败时不影响主流程：@Async + try-catch + error 日志
- [X] T026 为 query_audit_log 表添加按月分表策略注释

## Phase 9: Alerts & Cost Dashboard

- [X] T031 在 Java 后端创建 alert_rule 表和 AlertController：支持告警规则 CRUD 和启用/禁用
- [X] T032 创建 AlertCheckScheduler 预留（告警检查逻辑待对接通知模块）
- [X] T033 [Frontend] 在审计 API 层添加 LLM 成本统计接口
- [X] T034 [Frontend] 告警规则 API 已在 audit.ts 中实现（listAlertRules/createAlertRule/updateAlertRule/toggleAlertRule）

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

- [X] T027 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/audit.ts`
- [X] T028 [Frontend] 创建审计日志页面 `AuditLogList.vue`：统计卡片 + 多维度筛选 + 分页表格
- [X] T029 [Frontend] 创建慢查询页面 `SlowQueryList.vue`：按耗时降序 + SQL 详情
- [X] T030 [Frontend] 创建血缘查看页面 `LineageViewer.vue`：表级/字段级血缘 + 影响分析
