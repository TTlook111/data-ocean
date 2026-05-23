# 004 元数据治理

## 概述
元数据治理模块对采集到的元数据进行质量评估和状态管理，通过多维度质量检查（完整性、准确性、一致性、时效性、可追溯性）发现问题，并提供治理状态流转（DISCOVERED → NORMAL → RECOMMENDED / DEPRECATED / BLOCKED），决定哪些字段可以进入 RAG 被 SQL 生成引用。

## 解决的问题
- 元数据质量自动化评估（5 个维度、可配置规则）
- 质量问题发现、分派和处理闭环
- 表/字段级治理状态管理（控制 RAG 准入）
- 治理操作审计记录

## 实现方案
- **质量检查器模式**: 5 个独立 Checker 组件（Accuracy/Completeness/Consistency/Timeliness/Traceability），每个检查器实现特定维度的规则
- **规则引擎**: `metadata_quality_rule` 表存储可配置的质量规则，支持启用/停用和扣分权重
- **治理状态**: 字段级状态控制 RAG 准入 — NORMAL/RECOMMENDED 可进入，DEPRECATED/BLOCKED 禁止
- **审计追踪**: 每次状态变更记录到 `metadata_review_record`

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java`
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/governance/service/`
  - `QualityCheckService.java` / `impl/QualityCheckServiceImpl.java`
  - `QualityRuleService.java` / `impl/QualityRuleServiceImpl.java`
  - `QualityIssueService.java` / `impl/QualityIssueServiceImpl.java`
  - `GovernanceStatusService.java` / `impl/GovernanceStatusServiceImpl.java`
  - `MetadataReviewService.java` / `impl/MetadataReviewServiceImpl.java`
- Checker: `backend/DataOcean/src/main/java/com/dataocean/module/governance/checker/`
  - `QualityChecker.java`（接口）
  - `AccuracyChecker.java`, `CompletenessChecker.java`, `ConsistencyChecker.java`, `TimelinessChecker.java`, `TraceabilityChecker.java`
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/governance/entity/`
  - `MetadataQualityIssue.java`, `MetadataQualityRule.java`, `MetadataReviewRecord.java`

### 前端
- 页面:
  - `frontend/src/views/admin/governance/QualityDashboard.vue` — 质量看板
  - `frontend/src/views/admin/governance/IssueList.vue` — 问题清单
  - `frontend/src/views/admin/governance/StatusEditor.vue` — 治理状态编辑
- API: `frontend/src/api/admin/governance.ts`

### 数据库
- 迁移脚本: `V11__create_governance_tables.sql`
- 涉及表: `metadata_quality_issue`, `metadata_quality_rule`, `metadata_review_record`

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| POST | `/api/admin/snapshots/{snapshotId}/quality-check` | 触发质量校验 | 前端质量看板 |
| GET | `/api/admin/quality-rules` | 查询质量规则 | 前端规则配置 |
| PATCH | `/api/admin/quality-rules/{ruleId}` | 启用/停用规则 | 前端规则配置 |
| GET | `/api/admin/snapshots/{snapshotId}/quality-issues` | 查询质量问题 | 前端问题清单 |
| PATCH | `/api/admin/quality-issues/{issueId}/status` | 处理问题 | 前端问题清单 |
| PATCH | `/api/admin/quality-issues/batch-status` | 批量处理 | 前端问题清单 |
| POST | `/api/admin/quality-issues/{issueId}/assign` | 分派负责人 | 前端问题清单 |
| PATCH | `/api/admin/snapshots/{snapshotId}/tables/{tableName}/governance-status` | 更新表治理状态 | 前端治理状态页 |
| PATCH | `/api/admin/snapshots/{snapshotId}/columns/{columnId}/governance-status` | 更新字段治理状态 | 前端治理状态页 |
| PATCH | `/api/admin/snapshots/{snapshotId}/tables/{tableName}/batch-governance-status` | 批量更新字段状态 | 前端治理状态页 |
| GET | `/api/admin/snapshots/{snapshotId}/review-records` | 查询审核记录 | 前端治理状态页 |

## 模块间依赖
- **被依赖**: 006 知识库（发布前校验字段治理状态）、007 Schema RAG（RAG 准入控制）
- **依赖**: 003 元数据采集（基于快照数据做质量检查）
