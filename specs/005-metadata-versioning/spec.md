# Feature Specification: 元数据版本与审核模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 元数据不是一次性静态内容，数据库结构会持续变化，所以需要版本控制和审核流程。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员审核并发布元数据快照 (Priority: P1)

管理员查看一个已通过质量校验的快照，确认无高风险问题后发布，使其成为后续 skills.md 生成和 RAG 的依据。

**Why this priority**: 只有已发布的快照才能用于生成 skills.md 和 RAG 向量，这是治理闭环的关键节点。

**Independent Test**: 发布快照后，skills.md 生成功能能引用该快照；未发布的快照无法被引用。

**Acceptance Scenarios**:

1. **Given** 快照状态为 APPROVED, **When** 管理员点击"发布", **Then** 快照状态变为 PUBLISHED，可被 skills.md 生成引用
2. **Given** 快照存在未解决的高风险问题, **When** 管理员尝试发布, **Then** 系统阻止并提示"存在未解决的高风险问题"
3. **Given** 新快照已发布, **When** 查看旧快照, **Then** 旧快照自动标记为 EXPIRED

---

### User Story 2 - 查看快照版本历史 (Priority: P2)

管理员查看某个数据源的所有历史快照，了解元数据的演变过程。

**Why this priority**: 版本历史支持回溯和审计，但不影响核心发布流程。

**Independent Test**: 能看到所有历史快照及其状态、创建时间、质量分。

**Acceptance Scenarios**:

1. **Given** 数据源有 5 个历史快照, **When** 管理员打开版本历史, **Then** 按时间倒序显示所有快照及状态
2. **Given** 管理员选择两个快照, **When** 点击"对比", **Then** 显示两个版本之间的结构差异

---

### Edge Cases

- 快照发布后发现有问题能否撤回？（支持撤回到 APPROVED 状态，但已基于该快照生成的 skills.md 需要重新审核）
- 多个快照同时处于 PUBLISHED 状态？（同一数据源同一时间只允许一个 PUBLISHED 快照）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持快照状态流转：DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED
- **FR-002**: 系统 MUST 确保同一数据源同一时间只有一个 PUBLISHED 状态的快照
- **FR-003**: 系统 MUST 在新快照发布时自动将旧快照标记为 EXPIRED
- **FR-004**: 系统 MUST 禁止发布存在未解决高风险问题的快照
- **FR-005**: 系统 MUST 记录每次状态变更的操作人和时间
- **FR-006**: 系统 MUST 支持快照版本历史查看和对比

### Key Entities

- **MetadataSnapshot**: 扩展状态字段（DRAFT/CHECKING/ISSUE_FOUND/APPROVED/PUBLISHED/EXPIRED）、审核人、发布时间
- **SnapshotAuditLog**: 快照操作日志，包含快照ID、操作类型、操作人、时间、备注

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 未发布的快照 100% 不能被 skills.md 生成和 RAG 向量化引用
- **SC-002**: 快照状态流转全程可追溯（谁在什么时间做了什么操作）
- **SC-003**: 管理员能在 30 秒内完成快照的审核和发布操作

## Assumptions

- 快照发布是不可逆操作的最终确认，但支持紧急撤回
- EXPIRED 状态的快照保留数据不删除，用于历史审计
- 快照对比功能复用元数据采集模块的差异对比能力
