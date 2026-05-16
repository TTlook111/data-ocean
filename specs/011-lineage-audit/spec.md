# Feature Specification: 血缘与审计模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 血缘和审计负责回答"这次查询依据了什么、用了哪些表字段、结果从哪里来"。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 系统记录完整查询审计日志 (Priority: P1)

每次用户查询，系统自动记录完整的审计信息（谁、什么时间、问了什么、生成了什么 SQL、用了哪些表字段、结果如何）。

**Why this priority**: 审计日志是合规要求和问题排查的基础。

**Independent Test**: 查询完成后，审计日志中能找到该条记录，包含所有必要字段。

**Acceptance Scenarios**:

1. **Given** 用户完成一次查询, **When** 查看审计日志, **Then** 包含 user_id、datasource_id、question、generated_sql、execution_time、row_count、used_tables、used_fields
2. **Given** 查询失败, **When** 查看审计日志, **Then** 包含 error_message 和 is_success=false
3. **Given** 用户对结果点赞/踩, **When** 查看审计日志, **Then** user_feedback 字段已更新

---

### User Story 2 - 系统解析 SQL 生成血缘关系 (Priority: P2)

系统解析成功执行的 SQL，自动提取表级和字段级血缘关系，构建依赖图谱。

**Why this priority**: 血缘是变更影响分析的基础，但 MVP 阶段可以先做审计后做血缘。

**Independent Test**: 一条 JOIN 查询执行后，血缘图中能看到两张表的关联关系。

**Acceptance Scenarios**:

1. **Given** SQL 使用了 order_info JOIN user, **When** 血缘解析完成, **Then** 记录 order_info → user 的表级血缘
2. **Given** 某字段被删除, **When** 查看血缘图, **Then** 能找到所有依赖该字段的历史查询和 skills.md 片段

---

### User Story 3 - 管理员查看审计报表 (Priority: P2)

管理员在后台查看查询统计（按用户、数据源、时间维度），识别慢查询和异常模式。

**Why this priority**: 运维监控能力，帮助管理员发现问题和优化系统。

**Independent Test**: 能按数据源筛选出所有慢查询（>5秒）。

**Acceptance Scenarios**:

1. **Given** 管理员打开审计报表, **When** 按数据源筛选, **Then** 显示该数据源的查询统计（总数、成功率、平均耗时）
2. **Given** 存在执行超过 5 秒的查询, **When** 查看慢查询列表, **Then** 显示慢查询详情和优化建议

---

### Edge Cases

- 审计日志量过大时如何处理？（保留 180 天，超期归档或删除）
- 血缘解析遇到复杂 SQL（多层嵌套、CTE）时？（尽力解析，无法解析的标记为"复杂SQL"）
- 敏感字段的查询记录如何处理？（额外标记，支持安全审计筛选）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 记录每次查询的完整审计信息
- **FR-002**: 系统 MUST 解析成功 SQL 生成表级和字段级血缘
- **FR-003**: 系统 MUST 支持按用户、数据源、时间范围筛选审计日志
- **FR-004**: 系统 MUST 自动标记慢查询（执行超过 5 秒）
- **FR-005**: 系统 MUST 在表字段变更时通过血缘反查受影响的查询和知识片段
- **FR-006**: 系统 MUST 支持审计日志导出
- **FR-007**: 系统 MUST 保留审计日志 180 天（可配置）
- **FR-008**: 系统 MUST 支持将高频成功查询提升为模板

### Key Entities

- **query_audit_log**: 审计日志，包含 id、query_task_id、user_id、datasource_id、sql_text、used_tables、used_fields、row_count、success、created_at
- **query_lineage_table**: 表级血缘，包含 id、query_task_id、source_table、target_name、relation_type
- **query_lineage_column**: 字段级血缘，包含 id、query_task_id、source_table、source_column、expression、alias_name
- **llm_usage_log**: 模型调用成本，包含 id、query_task_id、provider、model、prompt_tokens、completion_tokens、cost_amount、created_at
- **quota_policy**: 查询配额策略，包含 id、subject_type、subject_id、daily_query_limit、monthly_cost_limit、enabled

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% 的查询都有完整审计记录
- **SC-002**: 血缘解析覆盖 90% 的成功查询 SQL
- **SC-003**: 表字段变更后 1 分钟内能通过血缘找到所有受影响的查询
- **SC-004**: 管理员能在 10 秒内按条件筛选到目标审计记录

## Assumptions

- 审计日志存储在 MySQL 管理库中，大量数据时考虑分表
- 血缘解析使用 sqlglot AST 提取表和字段引用
- 慢查询阈值可配置，默认 5 秒
