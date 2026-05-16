# Feature Specification: 数据源管理模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 数据源管理负责接入企业内部业务数据库，配置连接信息，测试连通性，管理启用禁用状态。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员添加数据源 (Priority: P1)

管理员在后台配置一个新的 MySQL 数据源，填写连接信息，系统测试连通性后保存。

**Why this priority**: 数据源是整个系统的数据基础，没有数据源就无法进行元数据采集和查询。

**Independent Test**: 添加数据源后，系统能成功连接该数据库并返回连通性测试结果。

**Acceptance Scenarios**:

1. **Given** 管理员在数据源管理页面, **When** 填写 JDBC URL、只读账号、密码并点击"测试连接", **Then** 系统执行 SELECT 1 并返回连通性结果
2. **Given** 连通性测试通过, **When** 点击保存, **Then** 数据源被创建，密码加密存储，状态为"已启用"
3. **Given** 连通性测试失败, **When** 点击保存, **Then** 系统阻止保存并提示连接失败原因

---

### User Story 2 - 管理员管理数据源状态 (Priority: P2)

管理员可以启用或禁用数据源。禁用后该数据源不出现在前台查询入口，且断开所有连接。

**Why this priority**: 数据源生命周期管理是运维基本能力。

**Independent Test**: 禁用数据源后，前台用户的数据源下拉列表中不再显示该数据源。

**Acceptance Scenarios**:

1. **Given** 数据源状态为启用, **When** 管理员点击"禁用", **Then** 数据源状态变为禁用，前台不可见，连接池销毁
2. **Given** 数据源状态为禁用, **When** 管理员点击"启用", **Then** 数据源恢复可用

---

### User Story 3 - 用户选择数据源进行查询 (Priority: P1)

业务用户在前台问答页面通过下拉框选择一个有权限的数据源，作为本次查询的目标库。

**Why this priority**: 数据源选择是查询流程的第一步，直接影响用户体验。

**Independent Test**: 用户只能看到自己有权限的已启用数据源。

**Acceptance Scenarios**:

1. **Given** 用户有 3 个数据源的访问权限, **When** 打开数据源选择器, **Then** 只显示这 3 个已启用的数据源
2. **Given** 用户没有任何数据源权限, **When** 打开问答页面, **Then** 提示"暂无可用数据源，请联系管理员"

---

### Edge Cases

- 数据源密码变更后，系统如何处理已缓存的连接池？（通知 Python 销毁对应连接池）
- 删除数据源时，关联的元数据、skills.md、向量数据如何处理？（级联清理或禁止删除有依赖的数据源）
- 同一数据库被重复添加时如何检测？（通过 host+port+dbname 去重提示）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持新增、编辑、删除数据源
- **FR-002**: 系统 MUST 在保存前执行连通性测试（SELECT 1），失败则阻止保存
- **FR-003**: 系统 MUST 使用 AES-256 加密存储数据库密码，禁止明文
- **FR-004**: 系统 MUST 强制要求配置只读账号（仅 SELECT 权限）
- **FR-005**: 系统 MUST 支持数据源启用/禁用状态管理
- **FR-006**: 系统 MUST 在禁用数据源时销毁对应的所有连接池
- **FR-007**: 系统 MUST 确保用户只能看到自己有权限的已启用数据源
- **FR-008**: 系统 MUST 限定每次查询只能在当前选中的单个数据源内执行
- **FR-009**: 系统 MUST 支持 MVP 阶段仅 MySQL 数据源类型

### Key Entities

- **datasource**: 数据源主表，包含 id、name、db_type、host、port、database_name、jdbc_url、readonly_username、status、owner_user_id
- **datasource_secret**: 数据源密钥，包含 id、datasource_id、encrypted_password、encrypt_algorithm、key_version、updated_at
- **datasource_access**: 库级访问授权，包含 id、subject_type（USER/ROLE/DEPARTMENT）、subject_id、datasource_id、can_query、can_export、can_view_sql
- **datasource_health_check**: 连通性检测记录，包含 id、datasource_id、check_status、latency_ms、error_message、checked_at

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 管理员能在 1 分钟内完成一个数据源的配置和连通性验证
- **SC-002**: 数据源密码在数据库中 100% 加密存储，任何日志中不出现明文密码
- **SC-003**: 禁用数据源后 5 秒内前台不再展示该数据源
- **SC-004**: 系统支持同时管理至少 20 个数据源

## Assumptions

- MVP 阶段仅支持 MySQL，后续扩展 PostgreSQL、Oracle
- 加密密钥通过环境变量或配置中心注入
- 数据源删除为软删除，保留历史审计记录
- 连接池配置：单数据源最大 10 连接（Python 端），5 连接（Java 端），全局上限 50
