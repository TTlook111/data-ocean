# Feature Specification: 权限与安全模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 权限体系需要覆盖功能权限、数据权限和结果权限，确保用户不能越权访问数据。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员配置数据源级访问权限 (Priority: P1)

管理员为用户/角色/部门配置可访问的数据源，用户只能查询被授权的数据源。

**Why this priority**: 数据源级权限是最基本的访问控制，防止用户看到不该看的数据库。

**Independent Test**: 用户未被授权的数据源不出现在前台下拉列表中。

**Acceptance Scenarios**:

1. **Given** 管理员为用户 A 授权数据源 1 和 2, **When** 用户 A 打开问答页面, **Then** 只能看到数据源 1 和 2
2. **Given** 用户 A 未被授权数据源 3, **When** 用户 A 尝试直接调用 API 查询数据源 3, **Then** 返回 403 权限不足

---

### User Story 2 - 管理员配置行列级权限 (Priority: P1)

管理员为用户/角色配置表级、列级访问控制和行级过滤策略。

**Why this priority**: 行列级权限是数据安全的核心，防止用户看到敏感数据。

**Independent Test**: 配置行级过滤后，用户查询结果只包含过滤后的数据。

**Acceptance Scenarios**:

1. **Given** 管理员为角色"华东区经理"配置行级过滤 region='华东', **When** 该角色用户查询, **Then** SQL 自动追加 WHERE region='华东'
2. **Given** 管理员将 salary 字段设为"禁止访问", **When** SQL 中包含 salary, **Then** 校验拒绝执行
3. **Given** 管理员将 phone 字段设为"脱敏", **When** 查询结果包含 phone, **Then** 返回脱敏后的值（138****5678）

---

### User Story 3 - 角色与功能权限管理 (Priority: P2)

管理员创建角色并分配功能权限（如"数据分析师"可编辑 skills.md，"普通用户"只能查询）。

**Why this priority**: 功能权限控制后台操作范围，但不影响核心查询流程。

**Independent Test**: 普通用户无法访问后台治理页面。

**Acceptance Scenarios**:

1. **Given** 角色"普通用户"无后台权限, **When** 该用户打开右上角用户菜单, **Then** 不展示"后台管理"入口
2. **Given** 角色"普通用户"无后台权限, **When** 该用户尝试访问后台 URL, **Then** 返回 403 或重定向到问答页面
3. **Given** 角色"数据分析师"有 skills.md 编辑权限, **When** 该用户进入后台, **Then** 能看到 skills.md 编辑入口

---

### Edge Cases

- 用户同时属于多个角色，权限如何合并？（取并集，最宽松原则）
- 行级过滤条件冲突时？（多条件 AND 合并）
- 管理员误删自己的管理员权限？（系统保留至少一个超级管理员，不允许删除）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持角色管理（增删改查）
- **FR-002**: 系统 MUST 支持数据源级访问控制（用户/角色/部门维度）
- **FR-003**: 系统 MUST 支持表级访问控制（允许/禁止访问特定表）
- **FR-004**: 系统 MUST 支持列级访问控制（允许/禁止/脱敏）
- **FR-005**: 系统 MUST 支持行级过滤策略配置
- **FR-006**: 系统 MUST 支持功能权限控制（菜单和操作级别）
- **FR-007**: 系统 MUST 确保权限在 SQL AST 层强制执行，不依赖 Prompt
- **FR-008**: 系统 MUST 对敏感字段查询结果执行脱敏
- **FR-009**: 系统 MUST 保留至少一个超级管理员账号不可删除
- **FR-010**: 系统 MUST 记录所有权限变更的审计日志
- **FR-011**: 系统 MUST 支持用户-角色分配管理（分配/移除角色、按角色查看用户列表）
- **FR-012**: 系统 MUST 实现部门权限继承（用户自动继承所属部门的数据源访问权限）
- **FR-013**: 系统 MUST 根据功能权限控制右上角用户菜单中的后台管理入口；无后台功能权限的用户不得看到入口，直接访问 `/admin/*` 也必须被拦截

### Key Entities

- **sys_role**: 角色（普通员工、数据分析师、数据管理员、安全管理员、超级管理员）
- **datasource_access**: 库级访问授权，包含 subject_type（USER/ROLE/DEPARTMENT）、subject_id、datasource_id、can_query、can_export、can_view_sql
- **datasource_access_policy**: 行列级策略，包含 datasource_id、subject_type、subject_id、table_name、column_name、row_filter_expression、mask_strategy
- **sys_permission**: 功能权限点，包含 permission_code、permission_name、module

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 未授权数据源的访问拦截率 100%
- **SC-002**: 行列级权限绕过率 0%
- **SC-003**: 敏感字段脱敏覆盖率 100%
- **SC-004**: 权限配置变更后 5 秒内生效
- **SC-005**: 10 个预设 Prompt 注入攻击样本 100% 被拦截或无害化（文档第 27.2 节验收标准）

## Assumptions

- 权限数据由 Java 层管理，每次查询时传给 Python（allowedTables、deniedColumns、maskColumns、rowFilters）
- 多角色权限合并采用并集策略（最宽松）
- 脱敏规则在 Java 网关层执行（返回前端之前）
- 结果控制：can_export=false 禁止导出，can_view_sql=false 不展示完整 SQL
- 行级过滤在 SQL AST 层注入，不依赖 Prompt

## Clarifications

### Session 2026-05-16

- Q: 角色有哪些？ → A: 普通员工、数据分析师、数据管理员、安全管理员、超级管理员（文档第 24.1 节）
- Q: 数据访问控制层次？ → A: 库级（can_query）→ 表级（governance_status）→ 列级（mask_strategy）→ 行级（row_filter_expression）→ 结果级（can_export/can_view_sql）（文档第 24.3 节）
- Q: 权限传递方式？ → A: Java 每次请求传入 allowedTables、deniedColumns、maskColumns、rowFilters 给 Python（文档第 5.1 节）
