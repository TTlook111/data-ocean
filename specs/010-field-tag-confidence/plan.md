# Implementation Plan: 字段 Tag 与可信度模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

字段 Tag 与可信度模块为 DataOcean 的核心治理能力，管理字段业务标签和 0-100 可信度评分。可信度分数直接影响 RAG 召回排序和 SQL 生成时的字段选择优先级。模块包含标签管理、可信度计算引擎、用户反馈收集和反馈审核四个子系统。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, Redis (Lettuce), Spring Validation

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL + Redis)

**Target Platform**: Linux server (Docker)

**Performance Goals**: 可信度更新 < 5s, 批量打标 < 2s (100 字段)

**Constraints**: 反馈限频依赖 Redis, 可信度分数 0-100 硬边界

## Project Structure

```text
backend/src/main/java/com/dataocean/module/fieldtag/
├── controller/
│   ├── FieldTagController.java
│   ├── FieldConfidenceController.java
│   ├── UserFeedbackController.java
│   └── FeedbackReviewController.java
├── service/
│   ├── FieldTagService.java
│   ├── FieldConfidenceService.java
│   ├── ConfidenceCalculator.java
│   ├── UserFeedbackService.java
│   └── FeedbackReviewService.java
├── mapper/
│   ├── FieldTagMapper.java
│   ├── FieldConfidenceMapper.java
│   ├── FieldConfidenceEventMapper.java
│   ├── UserFeedbackMapper.java
│   └── FeedbackReviewMapper.java
├── entity/
│   ├── FieldTag.java
│   ├── FieldConfidence.java
│   ├── FieldConfidenceEvent.java
│   ├── UserFeedback.java
│   └── FeedbackReview.java
└── dto/
    ├── FieldTagRequest.java
    ├── BatchTagRequest.java
    ├── ConfidenceVO.java
    ├── ConfidenceUpdateRequest.java
    ├── FeedbackRequest.java
    ├── FeedbackReviewRequest.java
    └── FeedbackVO.java

backend/src/main/resources/db/migration/
├── V10__create_field_tag_tables.sql
└── V11__init_predefined_tags.sql
```

## Implementation Phases

### Phase 1: 字段标签 CRUD

- 创建 field_tag 表和 Flyway 迁移
- 预定义标签初始化（金额/时间/状态/用户ID/敏感/废弃）
- 实现标签 CRUD API（单个和批量）
- 标签与 column_meta 关联校验

### Phase 2: 可信度引擎

- 创建 field_confidence 和 field_confidence_event 表
- 实现 ConfidenceCalculator：初始分计算（Schema=30, skills.md=60, 人工=90, 管理员=100）
- 实现可信度查询和管理员手动设置 API
- 事件日志记录每次变更

### Phase 3: 用户反馈与限频

- 创建 user_feedback 表
- 实现反馈提交 API（赞/踩 + 原因）
- Redis 限频：同一用户同一字段每天最多 1 次负向反馈（key: `feedback:neg:{userId}:{columnMetaId}`, TTL: 24h）
- 正向反馈直接触发可信度 +10

### Phase 4: 反馈审核与群体阈值

- 创建 feedback_review 表
- 实现审核队列 API（列表、通过、驳回）
- 审核通过后触发可信度 -15
- 群体阈值检测：3 个不同用户踩同一字段 → 自动 -5 并告警
- 告警通过 Spring Event 发布，后续对接通知模块

## Key Design Decisions

1. **可信度计算为事件驱动**: 每次变更产生 event 记录，score 字段为最终值（非增量累加），便于审计和回溯
2. **负向反馈必须审核**: 防止恶意刷踩，正向反馈直接生效（风险低）
3. **Redis 限频而非数据库**: 利用 TTL 自动过期，无需定时清理
4. **群体阈值独立于审核**: 即使无人审核，3 人踩也会自动降级，保证系统自愈
