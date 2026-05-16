# Tasks: 元数据版本与审核模块

**Input**: Design documents from `specs/005-metadata-versioning/`
**Prerequisites**: plan.md, spec.md, data-model.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V6__create_versioning_tables.sql`，包含 snapshot_audit_log 表的 DDL（字段：id、snapshot_id、action、old_status、new_status、operator_id、remark、created_at），含 snapshot_id 索引

## Phase 2: Foundational — 实体与 Mapper

- [ ] T002 [P] 创建快照审计日志实体类 `backend/src/main/java/com/dataocean/module/versioning/entity/SnapshotAuditLog.java`，字段：id、snapshotId、action（CREATE/STATUS_CHANGE/PUBLISH/EXPIRE/REVOKE）、oldStatus、newStatus、operatorId、remark、createdAt
- [ ] T003 [P] 创建快照状态枚举 `backend/src/main/java/com/dataocean/module/versioning/entity/SnapshotStatus.java`，值：DRAFT、CHECKING、ISSUE_FOUND、APPROVED、PUBLISHED、EXPIRED，包含合法流转路径校验方法 canTransitionTo(targetStatus)
- [ ] T004 [P] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/versioning/mapper/SnapshotAuditLogMapper.java`

## Phase 3: User Story 1 (P1) — 快照审核与发布

**Goal**: 管理员审核并发布元数据快照，使其成为 skills.md 生成和 RAG 的依据
**Independent Test**: 发布快照后，skills.md 生成功能能引用该快照；未发布的快照无法被引用

- [ ] T005 [US1] 创建审计日志服务 `backend/src/main/java/com/dataocean/module/versioning/service/SnapshotAuditLogService.java`，实现：recordStatusChange(snapshotId, oldStatus, newStatus, operatorId, remark)、listAuditLogs(snapshotId)
- [ ] T006 [US1] 创建快照生命周期服务 `backend/src/main/java/com/dataocean/module/versioning/service/SnapshotLifecycleService.java`，实现 changeStatus(snapshotId, targetStatus, operatorId, remark) 方法：校验当前状态是否允许流转到目标状态（使用 SnapshotStatus.canTransitionTo）→ 执行前置条件检查 → 更新快照状态 → 记录审计日志。前置条件规则：APPROVED→PUBLISHED 需检查无未解决 HIGH 问题；CHECKING→APPROVED 需质量校验通过
- [ ] T007 [US1] 创建快照发布服务 `backend/src/main/java/com/dataocean/module/versioning/service/SnapshotPublishService.java`，实现 publishSnapshot(snapshotId, operatorId) 方法：@Transactional 内执行——查询同数据源当前 PUBLISHED 快照 → 旧快照状态改为 EXPIRED → 新快照状态改为 PUBLISHED → 记录两条审计日志 → 发布 Spring ApplicationEvent（SnapshotPublishedEvent）
- [ ] T008 [US1] 创建发布事件类 `backend/src/main/java/com/dataocean/module/versioning/event/SnapshotPublishedEvent.java`，字段：snapshotId、datasourceId、operatorId、publishedAt
- [ ] T009 [US1] 创建过期事件类 `backend/src/main/java/com/dataocean/module/versioning/event/SnapshotExpiredEvent.java`，字段：snapshotId、datasourceId、expiredAt、replacedBySnapshotId
- [ ] T010 [US1] 创建状态变更请求 DTO `backend/src/main/java/com/dataocean/module/versioning/dto/SnapshotStatusChangeRequest.java`，字段：targetStatus、remark

## Phase 4: User Story 2 (P2) — 版本历史与对比

**Goal**: 管理员查看某个数据源的所有历史快照，了解元数据演变过程
**Independent Test**: 能看到所有历史快照及其状态、创建时间、质量分

- [ ] T011 [US2] 创建版本历史视图对象 `backend/src/main/java/com/dataocean/module/versioning/dto/SnapshotVersionHistoryVO.java`，字段：id、snapshotNo、publishStatus、qualityScore、tableCount、columnCount、createdAt、approvedBy、publishedAt
- [ ] T012 [US2] 创建审计日志视图对象 `backend/src/main/java/com/dataocean/module/versioning/dto/SnapshotAuditLogVO.java`，字段：id、action、oldStatus、newStatus、operatorName、remark、createdAt
- [ ] T013 [US2] 在 SnapshotLifecycleService 中添加 listVersionHistory(datasourceId, pageNum, pageSize) 方法，按 created_at DESC 返回该数据源所有快照的版本历史
- [ ] T014 [US2] 在 SnapshotLifecycleService 中添加 compareVersions(oldSnapshotId, newSnapshotId) 方法，复用 003 模块的 SchemaDiffService.compareSnapshots 实现跨版本对比

## Phase 5: 紧急撤回

- [ ] T015 在 SnapshotPublishService 中添加 revokeSnapshot(snapshotId, operatorId, reason) 方法：校验当前状态为 PUBLISHED → 状态改为 APPROVED → 记录审计日志（action=REVOKE）→ 检查是否有基于该快照生成的 skills.md，如有则标记 skills.md 需重新审核（写入告警记录）

## Phase 6: API 层

- [ ] T016 创建版本管理控制器 `backend/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java`，实现：GET /api/admin/snapshots/{datasourceId}/history（版本历史列表）、GET /api/admin/snapshots/{id}/audit-logs（审计日志）、PUT /api/admin/snapshots/{id}/status（状态流转）、POST /api/admin/snapshots/{id}/publish（发布）、POST /api/admin/snapshots/{id}/revoke（撤回）、GET /api/admin/snapshots/compare（版本对比，参数 oldId 和 newId）

## Phase 7: 事件监听集成

- [ ] T017 创建发布事件监听器占位 `backend/src/main/java/com/dataocean/module/versioning/event/SnapshotPublishedEventListener.java`，使用 @EventListener 监听 SnapshotPublishedEvent，当前仅记录日志，后续模块（skills.md 生成）实现具体逻辑

## Dependencies

```
T001 → T002-T004 (表结构先于实体)
T002-T004 → T005, T006 (实体先于 Service)
T005 → T006, T007 (审计日志服务先于生命周期和发布服务)
T003 → T006 (状态枚举先于生命周期服务)
T006 → T007 (生命周期服务先于发布服务)
T007 → T015 (发布逻辑先于撤回逻辑)
T008, T009 → T007 (事件类先于发布服务)
T006, T007, T015 → T016 (所有 Service 先于 Controller)
T008 → T017 (事件类先于监听器)
003-SchemaDiffService → T014 (跨模块依赖：差异对比复用 003 模块)
004-QualityIssueService → T006 (跨模块依赖：发布前检查未解决问题)
```

## Implementation Strategy

MVP-first: Phase 3（状态流转 + 发布）是核心，确保快照能走完 DRAFT→PUBLISHED 的完整生命周期。发布操作的事务原子性（新发布+旧过期）是关键技术点。Phase 4（版本历史）复用 003 模块的差异对比能力，不重复实现。Phase 5（紧急撤回）作为安全兜底。使用 Spring ApplicationEvent 解耦发布通知，下游模块（skills.md 生成、RAG 向量化）通过监听事件触发。
