# Tasks: 元数据治理模块

**Input**: Design documents from `specs/004-metadata-governance/`
**Prerequisites**: plan.md, spec.md, data-model.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V5__create_governance_tables.sql`，包含 metadata_quality_rule、metadata_quality_issue、metadata_review_record 三张表的 DDL，含索引

## Phase 2: Foundational — 实体与 Mapper

- [ ] T002 [P] 创建质量规则实体类 `backend/src/main/java/com/dataocean/module/governance/entity/MetadataQualityRule.java`，字段：id、ruleCode、ruleName、dimension（COMPLETENESS/ACCURACY/CONSISTENCY/TIMELINESS/TRACEABILITY）、severity（HIGH/MEDIUM/LOW）、checkLogic、enabled、description
- [ ] T003 [P] 创建质量问题实体类 `backend/src/main/java/com/dataocean/module/governance/entity/MetadataQualityIssue.java`，字段：id、snapshotId、datasourceId、ruleId、dimension、tableName、columnName、description、suggestion、severity、status（OPEN/CONFIRMED/RESOLVED/REJECTED/CLOSED）、assigneeId、resolvedBy、resolvedAt、createdAt
- [ ] T004 [P] 创建审核记录实体类 `backend/src/main/java/com/dataocean/module/governance/entity/MetadataReviewRecord.java`，字段：id、snapshotId、objectType（TABLE/COLUMN）、objectId、action（APPROVE/REJECT/CHANGE_STATUS）、oldValue、newValue、operatorId、remark、createdAt
- [ ] T005 [P] 创建 Mapper 接口：`backend/src/main/java/com/dataocean/module/governance/mapper/MetadataQualityRuleMapper.java`、`MetadataQualityIssueMapper.java`、`MetadataReviewRecordMapper.java`

## Phase 3: User Story 1 (P1) — 质量校验引擎

**Goal**: 对已采集的元数据快照执行五维质量校验，生成质量分和问题清单
**Independent Test**: 对一个缺少字段注释的数据库执行校验后，问题清单中能列出所有无注释字段

- [ ] T006 [US1] 创建质量规则服务 `backend/src/main/java/com/dataocean/module/governance/service/QualityRuleService.java`，实现：listEnabledRules()、getRulesByDimension(dimension)、initDefaultRules()（系统启动时初始化内置规则）
- [ ] T007 [US1] 创建 Flyway 脚本 `backend/src/main/resources/db/migration/V5_1__init_quality_rules.sql`，插入默认质量规则数据（字段注释缺失、表注释缺失、主键缺失、类型命名不匹配、同名字段类型不一致、外键缺失等）
- [ ] T008 [US1] 创建完整性校验器 `backend/src/main/java/com/dataocean/module/governance/checker/CompletenessChecker.java`，实现 Checker 接口，检测：字段注释缺失（column_comment 为空）、表注释缺失（table_comment 为空）、主键缺失（表无主键字段）
- [ ] T009 [US1] 创建准确性校验器 `backend/src/main/java/com/dataocean/module/governance/checker/AccuracyChecker.java`，检测：字段命名与类型不匹配（如 xxx_time/xxx_date 字段不是时间类型、xxx_id 字段不是整数类型）、枚举值异常（distinct_count 过高的疑似枚举字段）
- [ ] T010 [US1] 创建一致性校验器 `backend/src/main/java/com/dataocean/module/governance/checker/ConsistencyChecker.java`，检测：同名字段跨表数据类型不一致、同名字段注释冲突（不同表中同名字段注释不同）
- [ ] T011 [US1] 创建时效性校验器 `backend/src/main/java/com/dataocean/module/governance/checker/TimelinessChecker.java`，检测：快照过期（距上次同步超过配置天数）、表长期无数据更新（基于 information_schema.TABLES.UPDATE_TIME）
- [ ] T012 [US1] 创建可追溯性校验器 `backend/src/main/java/com/dataocean/module/governance/checker/TraceabilityChecker.java`，检测：外键关系缺失（有 xxx_id 命名但无外键定义）、孤立表（无任何关联关系的表）
- [ ] T013 [US1] 创建质量校验编排服务 `backend/src/main/java/com/dataocean/module/governance/service/QualityCheckService.java`，实现 executeQualityCheck(snapshotId) 方法：加载快照对应的表和字段数据 → 依次调用五个 Checker → 收集所有问题 → 批量写入 metadata_quality_issue 表 → 计算综合质量分（五维各占 20%，每维内按规则加权平均）→ 更新快照的 quality_score 字段
- [ ] T014 [US1] 创建质量校验请求 DTO `backend/src/main/java/com/dataocean/module/governance/dto/QualityCheckRequest.java`，字段：snapshotId
- [ ] T015 [US1] 创建质量校验结果视图 `backend/src/main/java/com/dataocean/module/governance/dto/QualityCheckResultVO.java`，字段：snapshotId、overallScore、dimensionScores（Map<String, Double>）、totalIssues、highIssues、mediumIssues、lowIssues

## Phase 4: User Story 2 (P1) — 问题清单管理

**Goal**: 数据负责人查看分派给自己的问题清单，逐条确认、修正或驳回
**Independent Test**: 处理完所有问题后，元数据快照状态可以推进到"已审核"

- [ ] T016 [US2] 创建问题清单服务 `backend/src/main/java/com/dataocean/module/governance/service/QualityIssueService.java`，实现：listIssuesBySnapshot(snapshotId, status, severity, pageNum, pageSize)、assignIssue(issueId, assigneeId)、batchAssign(issueIds, assigneeId)、confirmIssue(issueId, operatorId)、resolveIssue(issueId, operatorId, resolution)、rejectIssue(issueId, operatorId, reason)、closeIssue(issueId)、batchResolve(issueIds, operatorId, resolution)
- [ ] T017 [US2] 创建问题视图对象 `backend/src/main/java/com/dataocean/module/governance/dto/QualityIssueVO.java`，字段：id、dimension、tableName、columnName、description、suggestion、severity、status、assigneeName、createdAt、resolvedAt
- [ ] T018 [US2] 创建问题处理请求 DTO `backend/src/main/java/com/dataocean/module/governance/dto/IssueHandleRequest.java`，字段：action（CONFIRM/RESOLVE/REJECT）、resolution、reason
- [ ] T019 [US2] 实现新快照生成时旧问题自动关闭逻辑：在 QualityCheckService.executeQualityCheck 开始时，将该数据源旧快照的所有 OPEN/CONFIRMED 状态问题批量更新为 CLOSED

## Phase 5: User Story 3 (P2) — 治理状态管理

**Goal**: 管理员为表和字段设置治理状态，控制 RAG 准入
**Independent Test**: 将字段标记为 DEPRECATED 后，该字段不出现在 RAG 召回结果中

- [ ] T020 [US3] 创建治理状态服务 `backend/src/main/java/com/dataocean/module/governance/service/GovernanceStatusService.java`，实现：updateTableStatus(tableMetaId, newStatus, operatorId, reason)、updateColumnStatus(columnMetaId, newStatus, operatorId, reason)、batchUpdateStatus(objectType, objectIds, newStatus, operatorId, reason)，每次变更写入 metadata_review_record 审计日志
- [ ] T021 [US3] 创建治理状态更新请求 DTO `backend/src/main/java/com/dataocean/module/governance/dto/GovernanceStatusUpdateRequest.java`，字段：objectType（TABLE/COLUMN）、objectIds（List<Long>）、targetStatus（DISCOVERED/NORMAL/RECOMMENDED/DEPRECATED/SENSITIVE/BLOCKED）、reason
- [ ] T022 [US3] 创建 RAG 准入校验方法：在 GovernanceStatusService 中实现 isEligibleForRag(objectType, objectId) 方法，校验 governance_status 为 NORMAL 或 RECOMMENDED 且 review_status 为 APPROVED
- [ ] T023 [US3] 实现治理状态变更对 skills.md 的影响检查：当字段状态变为 DEPRECATED/BLOCKED 时，检查是否有已发布的 skills.md 引用该字段，如有则记录告警（写入 schema_change_event 表，risk_level=HIGH）

## Phase 6: 审核流程

- [ ] T024 创建审核服务 `backend/src/main/java/com/dataocean/module/governance/service/MetadataReviewService.java`，实现：submitForReview(snapshotId, submitterId)（检查所有 HIGH 问题已处理）、approveSnapshot(snapshotId, reviewerId, remark)、rejectSnapshot(snapshotId, reviewerId, reason)，每次操作记录 metadata_review_record

## Phase 7: API 层

- [ ] T025 创建治理控制器 `backend/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java`，实现：POST /api/admin/governance/quality-check（触发质量校验）、GET /api/admin/governance/issues（问题清单列表，支持按快照/状态/严重级别筛选分页）、PUT /api/admin/governance/issues/{id}/handle（处理单个问题）、PUT /api/admin/governance/issues/batch-handle（批量处理）、PUT /api/admin/governance/issues/{id}/assign（分派问题）、PUT /api/admin/governance/status（更新治理状态）、POST /api/admin/governance/review/submit（提交审核）、POST /api/admin/governance/review/approve（审核通过）、POST /api/admin/governance/review/reject（审核驳回）

## Dependencies

```
T001 → T002-T005 (表结构先于实体)
T002-T005 → T006, T016, T020, T024 (实体先于 Service)
T006, T007 → T008-T012 (规则服务和数据先于 Checker)
T008-T012 → T013 (各 Checker 先于编排服务)
T013 → T016 (校验生成问题后才能管理问题)
T016 → T024 (问题处理完才能提交审核)
T020 → T022, T023 (治理状态服务先于准入校验)
T013, T016, T020, T024 → T025 (所有 Service 先于 Controller)
```

## Implementation Strategy

MVP-first: Phase 3（质量校验引擎）是核心，五个 Checker 可并行开发。Phase 4（问题清单）紧随其后，确保校验结果可管理。Phase 5（治理状态）是 RAG 准入的基础。规则引擎 MVP 使用策略模式硬编码，不引入 Drools 等重型框架。治理状态直接存储在 003 模块的 db_table_meta/db_column_meta 表中，不额外建表。

## Phase 8: Frontend Pages

- [ ] T026 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/governance.ts`：触发质量校验、问题列表、问题处理、治理状态变更
- [ ] T027 [Frontend] 创建质量看板页面 `frontend/src/views/admin/governance/QualityDashboard.vue`：各数据源质量分雷达图、问题趋势折线图、治理完成率
- [ ] T028 [Frontend] 创建问题清单页面 `frontend/src/views/admin/governance/IssueList.vue`：按维度/严重级别/状态筛选、分派负责人、批量确认/驳回
- [ ] T029 [Frontend] 创建治理状态编辑器 `frontend/src/views/admin/governance/StatusEditor.vue`：表/字段列表 + 状态下拉（NORMAL/RECOMMENDED/DEPRECATED/SENSITIVE/BLOCKED）+ 批量操作
