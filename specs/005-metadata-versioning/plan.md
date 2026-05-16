# Implementation Plan: 元数据版本与审核模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-metadata-versioning/spec.md`

## Summary

元数据版本与审核模块管理快照的完整生命周期（DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED），确保只有经过质量校验和人工审核的快照才能发布，发布后的快照作为 skills.md 生成和 RAG 向量化的唯一依据。同一数据源同一时间只允许一个 PUBLISHED 快照。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, Spring Events

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL)

**Target Platform**: Linux server (Docker)

**Project Type**: Web service (REST API)

**Performance Goals**: 状态流转操作 < 1s, 版本历史查询 < 500ms

**Constraints**: 发布操作需事务保证（新发布 + 旧过期原子执行）

**Scale/Scope**: MVP 阶段，单数据源最多保留 50 个历史快照

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | 版本审核是治理闭环的关键节点 |
| II. SQL 安全与只读执行 | N/A | 不涉及业务库 |
| III. 三层分离架构 | ✅ PASS | 纯 Java 层 |
| IV. RAG 准入控制 | ✅ PASS | 只有 PUBLISHED 快照才能进入 RAG |
| V. 可信度驱动生成 | N/A | 不直接涉及 |
| VI. 渐进式 MVP | ✅ PASS | 核心流转 + 对比，不做复杂审批流 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/versioning/
├── controller/
│   └── SnapshotVersionController.java
├── service/
│   ├── SnapshotLifecycleService.java       # 快照状态流转核心
│   ├── SnapshotPublishService.java         # 发布逻辑（含旧版过期）
│   └── SnapshotAuditLogService.java        # 操作日志
├── mapper/
│   └── SnapshotAuditLogMapper.java
├── entity/
│   └── SnapshotAuditLog.java
├── dto/
│   ├── SnapshotStatusChangeRequest.java
│   ├── SnapshotVersionHistoryVO.java
│   └── SnapshotAuditLogVO.java
└── event/
    ├── SnapshotPublishedEvent.java         # 发布事件（通知下游模块）
    └── SnapshotExpiredEvent.java           # 过期事件

backend/src/main/resources/db/migration/
└── V6__create_versioning_tables.sql
```

## Implementation Phases

### Phase 1: 数据模型 + 审计日志

1. 创建 snapshot_audit_log 表（Flyway V6）
2. 实现 SnapshotAuditLog 实体和 Mapper
3. 实现 SnapshotAuditLogService

### Phase 2: 状态流转引擎

1. 实现 SnapshotLifecycleService
   - 状态机校验（合法流转路径）
   - 前置条件检查（如发布前无未解决 HIGH 问题）
   - 每次流转记录审计日志
2. 状态流转规则：
   - DRAFT → CHECKING: 触发质量校验
   - CHECKING → ISSUE_FOUND / APPROVED: 校验结果决定
   - ISSUE_FOUND → APPROVED: 所有 HIGH 问题已处理
   - APPROVED → PUBLISHED: 管理员确认发布
   - PUBLISHED → EXPIRED: 新版本发布时自动触发

### Phase 3: 发布逻辑

1. 实现 SnapshotPublishService
   - 事务内：新快照 → PUBLISHED + 旧快照 → EXPIRED
   - 唯一性约束：同一数据源只有一个 PUBLISHED
   - 发布后发送 Spring Event（SnapshotPublishedEvent）
   - 下游模块监听事件触发 skills.md 生成

### Phase 4: 版本历史 + 对比 + API

1. 实现版本历史查询（复用 003 模块的 SnapshotService）
2. 实现跨版本对比（复用 003 模块的 SchemaDiffService）
3. 实现 SnapshotVersionController
4. 集成测试

### Phase 5: 紧急撤回

1. PUBLISHED → APPROVED 撤回逻辑
2. 撤回时检查是否有基于该快照生成的 skills.md
3. 如有，标记 skills.md 需重新审核

## Key Technical Decisions

- 状态机使用枚举 + 校验方法实现，不引入状态机框架
- 发布操作使用 @Transactional 保证原子性
- 使用 Spring ApplicationEvent 解耦发布通知
- 版本对比复用 003 模块的 SchemaDiffService，不重复实现
- 审计日志独立于 metadata_review_record（004模块），专注快照级操作

## Complexity Tracking

无违规项。
