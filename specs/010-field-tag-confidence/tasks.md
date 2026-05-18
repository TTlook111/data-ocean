# Tasks: 字段 Tag 与可信度模块

**Input**: Design documents from `specs/010-field-tag-confidence/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V10__create_field_tag_tables.sql`，包含 field_tag、field_confidence、field_confidence_event、user_feedback、feedback_review 五张表的 DDL
- [ ] T002 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V11__init_predefined_tags.sql`，初始化预定义标签数据（金额类/时间类/状态类/用户ID类/敏感/废弃/推荐/阻断）
- [ ] T003 创建 Java 包结构 `backend/src/main/java/com/dataocean/module/fieldtag/`，包含 controller/service/mapper/entity/dto 子包

## Phase 2: Foundational — 字段标签 CRUD

- [ ] T004 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/fieldtag/entity/FieldTag.java`，包含 id、column_meta_id、tag_code、tag_name、source（SYSTEM/MANUAL/AI_SUGGESTED）、created_by、created_at 字段
- [ ] T005 [P] 实现 Mapper 接口 `backend/src/main/java/com/dataocean/module/fieldtag/mapper/FieldTagMapper.java`，继承 BaseMapper，添加自定义方法 selectByColumnMetaId、batchInsert
- [ ] T006 实现 `backend/src/main/java/com/dataocean/module/fieldtag/service/FieldTagService.java`：包含单个打标（addTag）、批量打标（batchAddTags，接收 List<columnMetaId> + tagCode）、移除标签（removeTag）、查询字段标签列表（getTagsByColumnMetaId）、按标签查询字段列表（getColumnsByTagCode）
- [ ] T007 实现 DTO 类 `backend/src/main/java/com/dataocean/module/fieldtag/dto/FieldTagRequest.java` 和 `BatchTagRequest.java`（包含 columnMetaIds: List<Long>、tagCode: String）
- [ ] T008 实现 `backend/src/main/java/com/dataocean/module/fieldtag/controller/FieldTagController.java`：POST /api/field-tags（单个打标）、POST /api/field-tags/batch（批量打标）、DELETE /api/field-tags/{id}（移除）、GET /api/field-tags/column/{columnMetaId}（查询字段标签）、GET /api/field-tags/by-tag/{tagCode}（按标签查字段）

## Phase 3: User Story 2 (P1) — 可信度引擎

**Goal**: 系统维护字段可信度分数
**Independent Test**: 字段被成功查询且用户点赞后，可信度分数增加

- [ ] T009 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/fieldtag/entity/FieldConfidence.java`，包含 id、column_meta_id、score（0-100）、level（HIGH/MEDIUM/LOW，由 score 自动计算：>=70 HIGH，>=40 MEDIUM，<40 LOW）、reason、updated_at、updated_by
- [ ] T010 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/fieldtag/entity/FieldConfidenceEvent.java`，包含 id、column_meta_id、delta_score、event_type（SCHEMA_INIT/SKILLS_MD_DEFINED/MANUAL_CONFIRM/ADMIN_SET/QUERY_SUCCESS/USER_LIKE/USER_DISLIKE_CONFIRMED/GROUP_THRESHOLD）、source_query_id、operator_id、created_at
- [ ] T011 [US2] 实现 `backend/src/main/java/com/dataocean/module/fieldtag/service/ConfidenceCalculator.java`：核心计算逻辑——initScore(source) 根据来源返回初始分（SCHEMA=30、SKILLS_MD=60、MANUAL=90、ADMIN=100）；adjustScore(columnMetaId, eventType, operatorId) 根据事件类型调整分数（QUERY_SUCCESS +2、USER_LIKE +10、USER_DISLIKE_CONFIRMED -15、GROUP_THRESHOLD -5），确保分数在 0-100 范围内，每次调整记录 FieldConfidenceEvent
- [ ] T012 [US2] 实现 `backend/src/main/java/com/dataocean/module/fieldtag/service/FieldConfidenceService.java`：包含初始化可信度（initConfidence）、查询可信度（getConfidence/batchGetConfidence）、管理员手动设置（adminSetScore，记录 ADMIN_SET 事件）、查询变更历史（getEventHistory）
- [ ] T013 [US2] 实现 `backend/src/main/java/com/dataocean/module/fieldtag/controller/FieldConfidenceController.java`：GET /api/field-confidence/{columnMetaId}（查询单个）、GET /api/field-confidence/batch（批量查询，接收 columnMetaIds 参数）、PUT /api/field-confidence/{columnMetaId}（管理员设置）、GET /api/field-confidence/{columnMetaId}/events（变更历史）

## Phase 4: User Story 3 (P2) — 用户反馈与限频

**Goal**: 用户反馈驱动可信度调整
**Independent Test**: 用户点踩后字段可信度不立即变化，管理员确认后才扣分

- [ ] T014 [P] [US3] 实现实体类 `backend/src/main/java/com/dataocean/module/fieldtag/entity/UserFeedback.java`，包含 id、query_task_id、column_meta_id、user_id、feedback_type（LIKE/DISLIKE）、reason_code、comment、created_at
- [ ] T015 [US3] 实现 `backend/src/main/java/com/dataocean/module/fieldtag/service/UserFeedbackService.java`：提交反馈（submitFeedback）——LIKE 类型直接触发 ConfidenceCalculator.adjustScore(USER_LIKE)；DISLIKE 类型先检查 Redis 限频（key: `feedback:neg:{userId}:{columnMetaId}`，TTL 24h），通过后创建反馈记录并进入审核队列，不直接调整可信度
- [ ] T016 [US3] 实现 `backend/src/main/java/com/dataocean/module/fieldtag/controller/UserFeedbackController.java`：POST /api/feedback（提交反馈，接收 FeedbackRequest：query_task_id、column_meta_id、feedback_type、reason_code、comment）

## Phase 5: 反馈审核与群体阈值

- [ ] T017 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/fieldtag/entity/FeedbackReview.java`，包含 id、feedback_id、review_status（PENDING/APPROVED/REJECTED）、reviewer_id、review_comment、handled_at
- [ ] T018 实现 `backend/src/main/java/com/dataocean/module/fieldtag/service/FeedbackReviewService.java`：审核队列列表（listPendingReviews，分页）、审核通过（approveFeedback，触发 ConfidenceCalculator.adjustScore(USER_DISLIKE_CONFIRMED, -15)）、审核驳回（rejectFeedback，不调整可信度）
- [ ] T019 在 UserFeedbackService 中实现群体阈值检测：每次新增 DISLIKE 反馈后，查询该 column_meta_id 近 7 天内不同 user_id 的 DISLIKE 数量，达到 3 个且无 APPROVED 审核记录时，自动触发 ConfidenceCalculator.adjustScore(GROUP_THRESHOLD, -5) 并通过 Spring ApplicationEvent 发布告警事件
- [ ] T020 实现 `backend/src/main/java/com/dataocean/module/fieldtag/controller/FeedbackReviewController.java`：GET /api/feedback-reviews（审核队列列表）、POST /api/feedback-reviews/{feedbackId}/approve（通过）、POST /api/feedback-reviews/{feedbackId}/reject（驳回）

## Phase 6: Polish & Cross-Cutting

- [ ] T021 实现所有 DTO/VO 类 `backend/src/main/java/com/dataocean/module/fieldtag/dto/`：ConfidenceVO（score、level、reason、lastUpdated）、ConfidenceUpdateRequest（score、reason）、FeedbackRequest、FeedbackVO、FeedbackReviewRequest
- [ ] T022 添加接口参数校验：score 范围 0-100、tagCode 非空且在预定义列表中、feedback_type 枚举校验、同一用户同一字段每天限频校验
- [ ] T023 在 FieldTagService 中添加标签与 column_meta 关联校验：打标前检查 column_meta_id 是否存在，不存在则返回 404

## Phase 8: Bulk Operations & Trend

- [ ] T028 在 Java 后端添加 GET /api/admin/fields/{fieldId}/confidence-trend?days=30 接口：返回该字段可信度分数的时序数据（从 field_confidence_event 聚合）
- [ ] T029 在 Java 后端添加 POST /api/admin/fields/import-tags 接口：接收 CSV 文件（column_id, tag_code），批量导入字段标签
- [ ] T030 在 Java 后端添加 POST /api/admin/fields/auto-tag 接口：根据字段名模式匹配自动打标（如 *_amount → 金额类, *_time/*_date → 时间类, *_status → 状态类）
- [ ] T031 [Frontend] 在可信度看板中为每个字段添加"趋势"按钮，点击弹出可信度变化折线图

## Dependencies

```
T001~T002 → T003 → T004~T005 → T006~T008
T001 → T009~T010 → T011~T013
T013 → T014~T016 (反馈依赖可信度引擎)
T015 → T017~T020 (审核依赖反馈)
T011 → T019 (群体阈值依赖 ConfidenceCalculator)
```

## Implementation Strategy

MVP-first approach:
1. 先完成标签 CRUD（Phase 2），这是最基础的数据管理能力
2. 再实现可信度引擎（Phase 3），打通初始分计算和手动设置
3. 然后做用户反馈（Phase 4），接入 Redis 限频
4. 最后做审核和群体阈值（Phase 5），完成闭环
5. 可信度数据通过 API 暴露给 Python 层，供 RAG 重排和 SQL 生成使用

## Phase 7: Frontend Pages

- [ ] T024 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/field.ts`：字段标签 CRUD、可信度查询、反馈审核
- [ ] T025 [Frontend] 创建字段标签管理页面 `frontend/src/views/admin/field/FieldTagManager.vue`：按数据源/表筛选字段、批量打标、标签类型下拉
- [ ] T026 [Frontend] 创建可信度看板页面 `frontend/src/views/admin/field/ConfidenceDashboard.vue`：分数分布柱状图、低可信字段列表、趋势变化
- [ ] T027 [Frontend] 创建反馈审核队列页面 `frontend/src/views/admin/field/FeedbackReview.vue`：待审核反馈列表、用户/字段/原因、通过/驳回操作
