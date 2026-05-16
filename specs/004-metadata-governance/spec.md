# Feature Specification: 元数据治理模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 元数据治理是本项目的关键，治理的不是查询结果，而是治理 AI 使用的一切依据。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 系统执行元数据质量校验 (Priority: P1)

管理员对一个已采集的元数据快照触发质量校验，系统自动检测完整性、准确性、一致性、时效性和可追溯性问题。

**Why this priority**: 质量校验是治理的核心动作，决定哪些元数据可以进入 RAG。

**Independent Test**: 对一个缺少字段注释的数据库执行校验后，问题清单中能列出所有无注释字段。

**Acceptance Scenarios**:

1. **Given** 元数据快照已生成, **When** 管理员触发质量校验, **Then** 系统按五维标准生成质量分和问题清单
2. **Given** 校验发现 30% 字段无注释, **When** 查看问题清单, **Then** 列出所有无注释字段并标记为"完整性问题"
3. **Given** 校验发现同名字段含义冲突, **When** 查看问题清单, **Then** 标记为"一致性问题"并列出冲突详情

---

### User Story 2 - 数据负责人处理治理问题 (Priority: P1)

分析师或数据负责人查看分派给自己的问题清单，逐条确认、修正或驳回。

**Why this priority**: 人工确认是治理闭环的关键环节，没有人工确认就没有可信元数据。

**Independent Test**: 处理完所有问题后，元数据快照状态可以推进到"已审核"。

**Acceptance Scenarios**:

1. **Given** 问题清单中有待处理项, **When** 负责人补充字段注释并确认, **Then** 该问题标记为已解决
2. **Given** 问题清单中有误报项, **When** 负责人驳回并说明理由, **Then** 该问题标记为已驳回
3. **Given** 所有高风险问题已处理, **When** 负责人提交审核, **Then** 快照状态推进为待审核

---

### User Story 3 - 管理员维护字段治理状态 (Priority: P2)

管理员为表和字段设置治理状态（DISCOVERED、NORMAL、RECOMMENDED、DEPRECATED、SENSITIVE、BLOCKED）。

**Why this priority**: 治理状态直接决定哪些内容能进入 RAG，是准入控制的基础。

**Independent Test**: 将字段标记为 DEPRECATED 后，该字段不出现在 RAG 召回结果中。

**Acceptance Scenarios**:

1. **Given** 字段当前状态为 DISCOVERED, **When** 管理员确认该字段可用并设为 NORMAL, **Then** 该字段允许进入 skills.md 和 RAG
2. **Given** 字段被标记为 BLOCKED, **When** RAG 执行召回, **Then** 该字段不会出现在召回结果中

---

### Edge Cases

- 大量字段（500+）需要逐一确认时如何提效？（支持批量操作和 AI 辅助建议）
- 治理状态变更后，已发布的 skills.md 中引用了该字段怎么办？（触发 skills.md 一致性告警）
- 同一字段在不同快照中状态不同如何处理？（以最新已发布快照为准）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持按五维标准（完整性、准确性、一致性、时效性、可追溯性）执行质量校验
- **FR-002**: 系统 MUST 生成元数据质量分（0-100）
- **FR-003**: 系统 MUST 生成问题清单并支持分派给负责人
- **FR-004**: 系统 MUST 支持问题的确认、修正、驳回、关闭操作
- **FR-005**: 系统 MUST 支持表字段治理状态管理（DISCOVERED/NORMAL/RECOMMENDED/DEPRECATED/SENSITIVE/BLOCKED）
- **FR-006**: 系统 MUST 确保只有 NORMAL 或 RECOMMENDED 且审核通过的内容才能进入 RAG
- **FR-007**: 系统 MUST 支持质量规则的自定义配置
- **FR-008**: 系统 MUST 在治理状态变更时检查对已发布 skills.md 的影响

### Key Entities

- **QualityRule**: 质量规则，包含规则ID、维度、检测逻辑、严重级别
- **QualityIssue**: 质量问题，包含问题ID、快照ID、表/字段、维度、描述、状态、负责人
- **GovernanceStatus**: 治理状态记录，包含表/字段ID、状态、变更人、变更时间、原因

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 质量校验能在 1 分钟内完成 200 张表的全量检测
- **SC-002**: 未通过治理的元数据 100% 不会进入 RAG 向量库
- **SC-003**: 问题清单覆盖率达到 95%（已知问题类型均能检出）
- **SC-004**: 负责人能在问题清单中 10 秒内理解每个问题的含义和处理建议

## Assumptions

- 质量规则可配置但系统提供默认规则集
- 治理状态变更需要记录操作日志（谁在什么时间改了什么）
- 批量操作支持一次性处理同类问题（如批量确认所有"无注释"问题）
