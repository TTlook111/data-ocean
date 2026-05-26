# Tasks: 字段 Tag 与可信度模块

**Input**: Design documents from `specs/010-field-tag-confidence/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [X] T001 创建 Flyway 迁移脚本 `V16__create_field_tag_tables.sql`，包含 field_tag、field_confidence、field_confidence_event、user_feedback、feedback_review 五张表的 DDL
- [X] T002 创建 Flyway 迁移脚本 `V17__init_predefined_tags.sql`，初始化预定义标签数据（金额类/时间类/状态类/用户ID类/敏感/废弃/推荐/阻断）
- [X] T003 创建 Java 包结构 `backend/src/main/java/com/dataocean/module/fieldtag/`，包含 controller/service/mapper/entity/dto 子包

## Phase 2: Foundational — 字段标签 CRUD

- [X] T004 [P] 实现实体类 `FieldTag.java`
- [X] T005 [P] 实现 Mapper 接口 `FieldTagMapper.java`，继承 BaseMapper，添加自定义方法 batchInsert
- [X] T006 实现 `FieldTagService.java`：包含单个打标、批量打标、移除标签、查询字段标签列表、按标签查询字段列表
- [X] T007 实现 DTO 类 `FieldTagRequestDTO.java` 和 `BatchTagRequestDTO.java`
- [X] T008 实现 `FieldTagController.java`：POST /api/field-tags、POST /api/field-tags/batch、DELETE /api/field-tags/{id}、GET /api/field-tags/column/{columnMetaId}、GET /api/field-tags/by-tag/{tagCode}

## Phase 3: User Story 2 (P1) — 可信度引擎

**Goal**: 系统维护字段可信度分数
**Independent Test**: 字段被成功查询且用户点赞后，可信度分数增加

- [X] T009 [P] [US2] 实现实体类 `FieldConfidence.java`
- [X] T010 [P] [US2] 实现实体类 `FieldConfidenceEvent.java`
- [X] T011 [US2] 实现 `ConfidenceCalculator.java`：核心计算逻辑
- [X] T012 [US2] 实现 `FieldConfidenceService.java`：包含初始化可信度、查询可信度、管理员手动设置、查询变更历史
- [X] T013 [US2] 实现 `FieldConfidenceController.java`：GET/PUT/GET events API

## Phase 4: User Story 3 (P2) — 用户反馈与限频

**Goal**: 用户反馈驱动可信度调整
**Independent Test**: 用户点踩后字段可信度不立即变化，管理员确认后才扣分

- [X] T014 [P] [US3] 实现实体类 `UserFeedback.java`
- [X] T015 [US3] 实现 `UserFeedbackService.java`：提交反馈（LIKE 直接加分，DISLIKE Redis 限频 + 审核队列 + 群体阈值检测）
- [X] T016 [US3] 实现 `UserFeedbackController.java`：POST /api/feedback

## Phase 5: 反馈审核与群体阈值

- [X] T017 [P] 实现实体类 `FeedbackReview.java`
- [X] T018 实现 `FeedbackReviewService.java`：审核队列列表、审核通过（触发 -15）、审核驳回
- [X] T019 在 UserFeedbackService 中实现群体阈值检测（3 个不同用户踩 → 自动 -5 + Spring Event 告警）
- [X] T020 实现 `FeedbackReviewController.java`：GET /api/feedback-reviews、POST approve/reject

## Phase 6: Polish & Cross-Cutting

- [X] T021 实现所有 DTO/VO 类：ConfidenceVO、ConfidenceUpdateRequestDTO、FeedbackRequestDTO、FeedbackVO、FeedbackReviewRequestDTO、FieldTagVO、ConfidenceEventVO、ConfidenceTrendPointVO
- [X] T022 添加接口参数校验：score 范围 0-100、tagCode 非空且在预定义列表中、feedback_type 枚举校验、同一用户同一字段每天限频校验
- [X] T023 在 FieldTagService 中添加标签与 column_meta 关联校验：打标前检查 column_meta_id 是否存在

## Phase 8: Bulk Operations & Trend

- [X] T028 在 Java 后端添加 GET /api/admin/fields/{fieldId}/confidence-trend?days=30 接口
- [X] T029 在 Java 后端添加 POST /api/admin/fields/import-tags 接口：接收 CSV 文件批量导入字段标签
- [X] T030 在 Java 后端添加 POST /api/admin/fields/auto-tag 接口：根据字段名模式匹配自动打标
- [ ] T031 [Frontend] 在可信度看板中为每个字段添加"趋势"按钮，点击弹出可信度变化折线图（已在 ConfidenceDashboard.vue 中实现表格形式趋势展示）

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

- [X] T024 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/field.ts`：字段标签 CRUD、可信度查询、反馈审核
- [X] T025 [Frontend] 创建字段标签管理页面 `frontend/src/views/admin/field/FieldTagManager.vue`
- [X] T026 [Frontend] 创建可信度看板页面 `frontend/src/views/admin/field/ConfidenceDashboard.vue`
- [X] T027 [Frontend] 创建反馈审核队列页面 `frontend/src/views/admin/field/FeedbackReview.vue`
