# Feature Specification: 字段 Tag 与可信度模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 字段 Tag 和可信度用于告诉 AI 哪些字段更可靠，哪些字段需要谨慎使用。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员为字段打标签 (Priority: P1)

管理员在治理工作台为字段打上业务标签（金额类、时间类、状态类、敏感等），标记字段是否推荐、废弃或阻断。

**Why this priority**: Tag 是可信度计算和 RAG 过滤的基础数据。

**Independent Test**: 为字段打上"废弃"标签后，该字段不出现在 RAG 召回结果中。

**Acceptance Scenarios**:

1. **Given** 管理员在字段详情页, **When** 选择标签"金额类"并保存, **Then** 字段 Tag 更新成功
2. **Given** 管理员将字段标记为"废弃", **When** RAG 执行召回, **Then** 该字段被过滤不召回
3. **Given** 管理员批量选择 10 个字段, **When** 统一打标"时间类", **Then** 10 个字段同时更新

---

### User Story 2 - 系统维护字段可信度分数 (Priority: P1)

系统根据 skills.md 定义、人工确认、查询反馈等事件动态计算和调整字段可信度分数（0-100）。

**Why this priority**: 可信度直接影响 SQL 生成时的字段选择，是系统智能化的核心。

**Independent Test**: 字段被成功查询且用户点赞后，可信度分数增加。

**Acceptance Scenarios**:

1. **Given** 字段仅有 Schema 注释, **When** 系统初始化可信度, **Then** 初始分为 30
2. **Given** 字段在 skills.md 中被定义, **When** skills.md 发布, **Then** 可信度提升到 60
3. **Given** 字段在成功查询中被使用且用户点赞, **When** 反馈事件触发, **Then** 可信度 +10（上限 100）

---

### User Story 3 - 用户反馈驱动可信度调整 (Priority: P2)

用户对查询结果点踩时，相关字段进入审核队列，管理员确认后才调整可信度。

**Why this priority**: 防恶意反馈机制保证可信度不被滥用。

**Independent Test**: 用户点踩后字段可信度不立即变化，管理员确认后才扣分。

**Acceptance Scenarios**:

1. **Given** 用户对查询结果点踩, **When** 选择原因"数据不准", **Then** 相关字段进入审核队列，可信度不变
2. **Given** 管理员确认踩有效, **When** 审核通过, **Then** 字段可信度 -15
3. **Given** 同一字段被 3 个不同用户踩且无人审核, **When** 触发群体阈值, **Then** 自动 -5 并告警管理员

---

### Edge Cases

- 同一用户对同一字段重复点踩？（每天最多 1 次）
- 可信度降到 0 后还能继续使用吗？（可以使用但 Prompt 中标记为"极低可信"）
- 管理员直接设置可信度为 100 后还会被反馈降低吗？（会，但管理员设置的权重更高）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持为字段打业务标签（金额、时间、状态、用户ID、敏感、废弃等）
- **FR-002**: 系统 MUST 维护字段 0-100 的可信度分数
- **FR-003**: 系统 MUST 根据来源设定初始分（Schema 注释 30、skills.md 定义 60、人工确认 90、管理员设定 100）
- **FR-004**: 系统 MUST 根据查询反馈动态调整可信度（点赞 +10、成功使用 +2、确认踩 -15）
- **FR-005**: 系统 MUST 确保普通用户反馈不直接改变可信度，进入审核队列
- **FR-006**: 系统 MUST 限制同一用户对同一字段每天最多 1 次负向反馈
- **FR-007**: 系统 MUST 在 3 个不同用户踩同一字段时自动降级 -5 并告警
- **FR-008**: 系统 MUST 支持批量打标操作

### Key Entities

- **field_tag**: 字段标签，包含 id、column_meta_id、tag_code、tag_name、source、created_by
- **field_confidence**: 字段可信度，包含 id、column_meta_id、score（0-100）、level（高/中/低）、reason、updated_at
- **field_confidence_event**: 可信度变更流水，包含 id、column_meta_id、delta_score、event_type、source_query_id、operator_id、created_at
- **user_feedback**: 用户反馈，包含 id、query_task_id、user_id、feedback_type（赞/踩）、reason_code、comment、created_at
- **feedback_review**: 反馈审核，包含 id、feedback_id、review_status、reviewer_id、review_comment、handled_at

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 可信度分数能在反馈事件后 5 秒内更新
- **SC-002**: 废弃字段在 SQL 生成中出现率为 0%
- **SC-003**: 恶意反馈（单用户刷踩）100% 被限频机制拦截
- **SC-004**: 管理员能在审核队列中 30 秒内完成一条反馈的审核

## Assumptions

- 可信度计算逻辑在 Java 层实现，Python 层只消费可信度数据
- Tag 体系预定义一组标准标签，支持管理员自定义扩展
- 可信度变更全程记录事件日志，支持审计追溯
- 阶段一即包含完整的可信度动态调整（含正向反馈 +2/+10、负向审核队列 -15、群体自动降级 -5）

## Clarifications

### Session 2026-05-16

- Q: 阶段一是否包含可信度自动升降？ → A: 是，阶段一做完整的动态调整（含反馈驱动升降）
- Q: 数据库表结构？ → A: field_tag、field_confidence、field_confidence_event、user_feedback、feedback_review（文档第 23.5 节）
