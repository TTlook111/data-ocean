# Feature Specification: skills.md 业务知识库模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: skills.md 是业务语义说明书，由元数据治理结果生成草稿，再由人审核发布，是 RAG 的核心知识来源。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 系统基于元数据快照生成 skills.md 草稿 (Priority: P1)

管理员选择一个已发布的元数据快照，系统调用 AI 自动生成 skills.md 草稿，包含核心表说明、Join Path、指标候选等。

**Why this priority**: AI 生成草稿大幅降低人工编写成本，是 skills.md 生产效率的关键。

**Independent Test**: 生成的草稿包含所有必填模块（文档来源、核心表、Join Path、指标口径、字段防坑），且引用的表字段在快照中存在。

**Acceptance Scenarios**:

1. **Given** 已发布元数据快照存在, **When** 管理员点击"生成 skills.md 草稿", **Then** AI 读取快照中的表、字段、注释、索引、外键，生成结构化草稿
2. **Given** 草稿生成完成, **When** 查看草稿内容, **Then** 包含文档来源（绑定 snapshot_id）、核心表说明、Join Path、指标候选、字段防坑
3. **Given** 快照中某些表无注释, **When** 生成草稿, **Then** AI 基于字段名和类型推测用途，并标记为"待人工确认"

---

### User Story 2 - 分析师审核并发布 skills.md (Priority: P1)

分析师在线编辑 skills.md 草稿，补充业务流程、指标口径和防坑说明，提交审核后由管理员发布。

**Why this priority**: 人工审核是保证 skills.md 质量的关键环节，直接影响 RAG 召回和 SQL 生成质量。

**Independent Test**: 发布后的 skills.md 能被向量化模块检测到并触发向量化任务。

**Acceptance Scenarios**:

1. **Given** skills.md 处于草稿状态, **When** 分析师编辑并提交审核, **Then** 状态变为"待审核"
2. **Given** skills.md 处于待审核状态, **When** 管理员审核通过并发布, **Then** 状态变为"已发布"，触发向量化任务
3. **Given** 发布前校验发现引用了 DEPRECATED 字段, **When** 尝试发布, **Then** 系统阻止并提示具体问题

---

### User Story 3 - 版本管理与回滚 (Priority: P2)

管理员查看 skills.md 的历史版本，对比差异，必要时回滚到旧版本。

**Why this priority**: 版本管理保证知识库可追溯和可恢复，但不影响核心发布流程。

**Independent Test**: 回滚后，RAG 使用的是回滚后的版本内容。

**Acceptance Scenarios**:

1. **Given** skills.md 有 3 个历史版本, **When** 管理员查看版本列表, **Then** 显示每个版本的创建时间、审核人、状态
2. **Given** 管理员选择回滚到 v2, **When** 确认回滚, **Then** 创建新版本（内容为 v2），触发重新向量化

---

### Edge Cases

- 多人同时编辑同一个 skills.md？（乐观锁，保存时检查版本号冲突）
- skills.md 引用的表在新快照中被删除？（发布前校验拦截，提示更新引用）
- 向量化失败时查询使用哪个版本？（继续使用旧版本，不中断服务）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持基于已发布元数据快照自动生成 skills.md 草稿
- **FR-002**: 系统 MUST 支持 Markdown 在线编辑
- **FR-003**: 系统 MUST 支持状态流转：草稿 → 待审核 → 审核通过 → 已发布 → 已废弃
- **FR-004**: 系统 MUST 每个版本绑定 metadata_snapshot_id
- **FR-005**: 系统 MUST 在发布前校验引用的表字段是否存在、是否已审核、是否被阻断
- **FR-006**: 系统 MUST 支持版本对比和回滚
- **FR-007**: 系统 MUST 使用乐观锁防止并发编辑冲突
- **FR-008**: 系统 MUST 仅允许"数据分析师"和"管理员"角色编辑 skills.md
- **FR-009**: 系统 MUST 提供标准模板（文档来源、核心表、Join Path、指标口径、字段防坑等必填模块）

### Key Entities

- **knowledge_doc**: skills.md 主文档，包含 id、datasource_id、title、content、current_version、status、review_status、updated_by
- **knowledge_doc_version**: 文档版本，包含 id、doc_id、datasource_id、metadata_snapshot_id、version_no、content、generation_source、review_status、reviewer_id、change_summary、created_by、created_at
- **knowledge_chunk**: 知识切片，包含 id、doc_id、version_no、metadata_snapshot_id、chunk_type、chunk_text、related_table、related_column、review_status、vector_status
- **knowledge_review_task**: 知识审核任务，包含 id、doc_version_id、reviewer_id、review_status、review_comment、submitted_at、reviewed_at
- **vector_index_task**: 向量化任务，包含 id、datasource_id、target_type、target_id、status、started_at、finished_at、error_message

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: AI 生成的草稿覆盖 80% 以上的必填模块内容
- **SC-002**: 分析师审核一个 skills.md 的平均时间不超过 30 分钟
- **SC-003**: 只有已发布版本的 skills.md 能进入 RAG，草稿和待审核版本 100% 不可召回
- **SC-004**: 版本回滚后 5 分钟内新版本向量化完成并生效

## Assumptions

- AI 生成草稿使用 Qwen API，Prompt 模板由 Prompt 管理模块维护
- 向量化任务由定时任务（每 5 分钟）扫描触发
- skills.md 内容存储在数据库中，不使用文件系统
