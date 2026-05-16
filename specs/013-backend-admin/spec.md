# Feature Specification: 后台治理管理端模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 后台治理端面向管理员、数据分析师、数据管理员和安全管理员，是系统可信度的核心入口。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员使用治理工作台 (Priority: P1)

管理员登录后台，通过统一的治理工作台管理数据源、元数据、skills.md、字段 Tag 和可信度。

**Why this priority**: 后台是所有治理操作的入口，没有后台就无法完成治理闭环。

**Independent Test**: 管理员能从后台完成数据源配置 → 元数据同步 → 治理 → skills.md 发布的完整流程。

**Acceptance Scenarios**:

1. **Given** 管理员登录后台, **When** 进入治理工作台, **Then** 看到数据源列表、元数据状态、待处理问题数、待审核项数
2. **Given** 管理员点击某个数据源, **When** 进入详情, **Then** 看到该数据源的元数据快照、skills.md 版本、治理状态概览

---

### User Story 2 - 分析师处理元数据问题 (Priority: P1)

分析师查看分派给自己的元数据问题清单，逐条处理并提交。

**Why this priority**: 问题处理是治理流程的核心人工环节。

**Independent Test**: 分析师能看到自己的待办问题，处理后问题状态更新。

**Acceptance Scenarios**:

1. **Given** 分析师登录后台, **When** 进入"我的待办", **Then** 显示分派给自己的问题清单
2. **Given** 分析师处理完一个问题, **When** 点击"确认", **Then** 问题状态更新，待办数减少

---

### User Story 3 - 管理员查看质量看板 (Priority: P2)

管理员通过质量看板了解各数据源的元数据治理进度和质量分。

**Why this priority**: 看板提供全局视角，帮助管理员把控治理进度。

**Independent Test**: 看板能展示各数据源的质量分、问题数、治理完成率。

**Acceptance Scenarios**:

1. **Given** 管理员打开质量看板, **When** 页面加载, **Then** 展示各数据源的质量分雷达图、问题趋势、治理完成率
2. **Given** 某数据源质量分低于 60, **When** 查看看板, **Then** 该数据源标红告警

---

### Edge Cases

- 不同角色看到的后台功能不同？（按角色权限控制菜单和操作按钮）
- 后台操作需要审计吗？（所有治理操作记录操作日志）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 后台 MUST 提供统一的治理工作台入口
- **FR-002**: 后台 MUST 支持数据源管理（增删改查、连通性测试）
- **FR-003**: 后台 MUST 支持元数据同步触发和快照管理
- **FR-004**: 后台 MUST 支持元数据质量看板（质量分、问题数、趋势）
- **FR-005**: 后台 MUST 支持元数据问题清单处理
- **FR-006**: 后台 MUST 支持表字段治理状态维护
- **FR-007**: 后台 MUST 支持 skills.md 编辑、审核、发布
- **FR-008**: 后台 MUST 支持 Prompt 模板管理
- **FR-009**: 后台 MUST 支持字段 Tag 和可信度管理
- **FR-010**: 后台 MUST 支持用户反馈审核
- **FR-011**: 后台 MUST 支持查询审计查看和导出
- **FR-012**: 后台 MUST 按角色控制功能权限

### Key Entities

- 复用其他模块定义的实体，后台是各模块的 UI 聚合层

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 管理员能在后台完成完整的治理闭环（从数据源配置到 skills.md 发布）
- **SC-002**: 后台页面加载时间不超过 3 秒
- **SC-003**: 所有治理操作都有操作日志可追溯
- **SC-004**: 不同角色只能看到和操作自己权限范围内的功能

## Assumptions

- 后台使用 Vue 3 + Vite + TypeScript + Element Plus
- 后台和前端问答端是同一个 Vue 项目的不同路由模块（/admin/* vs /query/*）
- 角色权限控制通过前端路由守卫 + 后端接口鉴权双重保障
- 状态管理使用 Pinia

## Clarifications

### Session 2026-05-16

- Q: 前端问答端和后台治理端的部署关系？ → A: 同一个 Vue 项目，路由区分（/query/* 和 /admin/*）
