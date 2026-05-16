# Feature Specification: Prompt 管理模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: Prompt 不应该硬编码在代码中，而应该统一管理，让 AI 行为可调试、可回滚、可审计。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员编辑和发布 Prompt 模板 (Priority: P1)

管理员在后台查看所有 Prompt 模板，编辑模板内容，保存后自动生成新版本。

**Why this priority**: Prompt 模板直接决定 AI 生成质量，是系统调优的核心手段。

**Independent Test**: 修改 sql_generation 模板后，下次查询使用新版本 Prompt。

**Acceptance Scenarios**:

1. **Given** 管理员打开 Prompt 管理页面, **When** 页面加载, **Then** 显示所有模板列表（sql_generation、chart_generation、intent_recognition 等）
2. **Given** 管理员编辑 sql_generation 模板, **When** 保存, **Then** 自动创建新版本（version +1），旧版本保留
3. **Given** 新版本已保存, **When** 下次查询触发 SQL 生成, **Then** 使用最新 is_active 版本的模板

---

### User Story 2 - 管理员回滚 Prompt 版本 (Priority: P2)

管理员发现新版本 Prompt 效果不好，回滚到旧版本。

**Why this priority**: 回滚能力保证 Prompt 调优的安全性。

**Independent Test**: 回滚后，查询使用旧版本 Prompt 生成 SQL。

**Acceptance Scenarios**:

1. **Given** 模板有 3 个历史版本, **When** 管理员查看版本列表, **Then** 显示每个版本的内容、修改人、修改时间
2. **Given** 管理员选择回滚到 v2, **When** 确认回滚, **Then** v2 变为 is_active，v3 变为非活跃

---

### User Story 3 - Token 预算控制 (Priority: P2)

系统在渲染 Prompt 时按优先级分配 Token 预算，超出时从低优先级部分裁剪。

**Why this priority**: Token 预算控制防止 Prompt 过长导致 LLM 性能下降或超出限制。

**Independent Test**: 当 Schema 内容过多时，低优先级的 Few-shot 模板被裁剪。

**Acceptance Scenarios**:

1. **Given** Prompt 总 Token 超过 4000, **When** 渲染 Prompt, **Then** 从优先级最低的部分开始裁剪
2. **Given** 用户问题 + Schema 已占 1500 Token, **When** 计算剩余预算, **Then** skills.md 分配 1000、Few-shot 800、上下文 500、可信度 200

---

### Edge Cases

- 模板中的占位符变量不存在时？（渲染时跳过该变量，记录告警）
- 所有版本都被标记为非活跃？（系统保证至少一个版本为 active）
- 多人同时编辑同一模板？（乐观锁，后保存者提示冲突）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 将所有 Prompt 模板存储在数据库中，禁止硬编码
- **FR-002**: 系统 MUST 支持 Prompt 模板的版本管理（每次修改自动递增版本号）
- **FR-003**: 系统 MUST 支持 Prompt 版本回滚
- **FR-004**: 系统 MUST 支持模板中使用 {{变量}} 占位符
- **FR-005**: 系统 MUST 实现 Token 预算控制（默认 4000 Token，按优先级裁剪）
- **FR-006**: 系统 MUST 保证同一模板名同一时间只有一个 is_active 版本
- **FR-007**: 系统 MUST 记录每次修改的操作人和时间
- **FR-008**: 系统 MUST 提供模板预览功能（填入示例变量后展示渲染结果）

### Key Entities

- **prompt_template**: Prompt 模板，包含 id、template_code、template_name、scenario、content、current_version、enabled
- **prompt_template_version**: Prompt 版本，包含 id、template_id、version_no、content、change_summary、created_by、created_at

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 代码中 0 处硬编码 Prompt
- **SC-002**: Prompt 修改后 5 秒内新版本生效
- **SC-003**: Token 预算超出时 100% 按优先级裁剪，不会超出限制
- **SC-004**: 管理员能在 1 分钟内完成 Prompt 修改和发布

## Assumptions

- Prompt 模板存储在 Java 管理库中，Python 运行时通过内部 API 获取
- Token 计算使用 tiktoken 或模型提供的 tokenizer
- MVP 阶段提供 5 个核心模板：sql_generation、chart_generation、intent_recognition、schema_retrieval_query、memory_extraction
