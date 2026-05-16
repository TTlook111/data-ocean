# Implementation Plan: 元数据治理模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-metadata-governance/spec.md`

## Summary

元数据治理模块负责对采集到的元数据执行五维质量校验（完整性、准确性、一致性、时效性、可追溯性），生成问题清单，支持人工确认/修正/驳回，并管理表和字段的治理状态（DISCOVERED → NORMAL/RECOMMENDED/DEPRECATED/SENSITIVE/BLOCKED）。治理状态直接决定哪些内容能进入 RAG。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, Spring Validation

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL)

**Target Platform**: Linux server (Docker)

**Project Type**: Web service (REST API)

**Performance Goals**: 质量校验 1000 字段 < 30s, 问题清单查询 < 500ms

**Constraints**: 治理状态变更需记录审计日志

**Scale/Scope**: MVP 阶段，规则引擎使用硬编码规则，后续迭代支持自定义

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | 本模块是治理核心 |
| II. SQL 安全与只读执行 | N/A | 不涉及业务库查询 |
| III. 三层分离架构 | ✅ PASS | 纯 Java 层 |
| IV. RAG 准入控制 | ✅ PASS | 只有 NORMAL/RECOMMENDED + APPROVED 才能进 RAG |
| V. 可信度驱动生成 | ✅ PASS | 治理结果影响可信度评分 |
| VI. 渐进式 MVP | ✅ PASS | 先实现核心规则，自定义规则后续迭代 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/governance/
├── controller/
│   └── MetadataGovernanceController.java
├── service/
│   ├── QualityCheckService.java            # 质量校验编排
│   ├── QualityRuleService.java             # 规则管理
│   ├── QualityIssueService.java            # 问题清单管理
│   ├── GovernanceStatusService.java        # 治理状态管理
│   └── MetadataReviewService.java          # 审核流程
├── checker/
│   ├── CompletenessChecker.java            # 完整性校验
│   ├── AccuracyChecker.java                # 准确性校验
│   ├── ConsistencyChecker.java             # 一致性校验
│   ├── TimelinessChecker.java              # 时效性校验
│   └── TraceabilityChecker.java            # 可追溯性校验
├── mapper/
│   ├── MetadataQualityRuleMapper.java
│   ├── MetadataQualityIssueMapper.java
│   └── MetadataReviewRecordMapper.java
├── entity/
│   ├── MetadataQualityRule.java
│   ├── MetadataQualityIssue.java
│   └── MetadataReviewRecord.java
└── dto/
    ├── QualityCheckRequest.java
    ├── QualityCheckResultVO.java
    ├── QualityIssueVO.java
    ├── IssueHandleRequest.java
    └── GovernanceStatusUpdateRequest.java

backend/src/main/resources/db/migration/
└── V5__create_governance_tables.sql
```

## Implementation Phases

### Phase 1: 数据模型 + 规则框架

1. 创建数据库表（Flyway V5）
2. 实现实体类和 Mapper
3. 实现 QualityRuleService（规则 CRUD）
4. 初始化内置规则数据

### Phase 2: 五维质量校验引擎

1. 实现 CompletenessChecker
   - 字段注释缺失检测
   - 表注释缺失检测
   - 主键缺失检测
2. 实现 AccuracyChecker
   - 字段类型与命名不匹配（如 xxx_time 不是时间类型）
   - 枚举值异常检测
3. 实现 ConsistencyChecker
   - 同名字段跨表类型不一致
   - 同名字段注释冲突
4. 实现 TimelinessChecker
   - 表长期无更新检测（基于 UPDATE_TIME）
   - 快照过期检测
5. 实现 TraceabilityChecker
   - 外键关系缺失检测
   - 孤立表检测
6. 实现 QualityCheckService 编排所有 Checker，计算综合质量分

### Phase 3: 问题清单管理

1. 实现 QualityIssueService
   - 问题创建（校验结果自动生成）
   - 问题状态流转：OPEN → CONFIRMED → RESOLVED / REJECTED
   - 问题分派（指定负责人）
   - 批量操作支持
2. 问题严重级别：HIGH / MEDIUM / LOW

### Phase 4: 治理状态管理

1. 实现 GovernanceStatusService
   - 表级治理状态变更
   - 字段级治理状态变更
   - 批量状态变更
   - 状态变更审计日志
2. RAG 准入校验逻辑（供后续模块调用）

### Phase 5: API + 测试

1. 实现 MetadataGovernanceController
2. 单元测试（各 Checker 独立测试）
3. 集成测试

## Key Technical Decisions

- 质量分计算：五维各占 20%，每维内按规则加权平均
- 规则引擎 MVP 使用策略模式（Checker 接口），不引入 Drools
- 治理状态存储在 db_table_meta / db_column_meta 表中（003模块的表），不单独建表
- 问题清单与快照关联，新快照生成时旧问题自动关闭

## Complexity Tracking

无违规项。
