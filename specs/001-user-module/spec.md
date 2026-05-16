# Feature Specification: 用户模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 用户模块负责平台用户、角色、部门和基础登录状态管理，是权限控制、审计追踪和查询归属的基础。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员创建用户并分配角色 (Priority: P1)

管理员登录后台，新增一个业务用户，为其绑定部门和角色，使该用户能够登录系统并获得对应权限。

**Why this priority**: 用户是整个系统的基础实体，没有用户就无法进行任何后续操作（数据源授权、查询、审计归属）。

**Independent Test**: 创建用户后，该用户能成功登录系统并看到其角色对应的功能菜单。

**Acceptance Scenarios**:

1. **Given** 管理员已登录后台, **When** 填写用户名、姓名、部门、角色并提交, **Then** 系统创建用户并返回成功提示
2. **Given** 用户已被创建且状态为正常, **When** 该用户使用账号密码登录, **Then** 系统验证通过并跳转到对应角色的首页
3. **Given** 管理员创建用户时填写了已存在的用户名, **When** 提交表单, **Then** 系统提示"用户名已存在"

---

### User Story 2 - 用户登录与会话管理 (Priority: P1)

用户通过账号密码登录系统，系统颁发 Token，后续请求携带 Token 进行鉴权。退出登录时 Token 失效。

**Why this priority**: 登录是所有功能的入口，鉴权机制是安全基础。

**Independent Test**: 用户登录后能访问受保护接口，退出后再访问返回 401。

**Acceptance Scenarios**:

1. **Given** 用户状态为正常, **When** 输入正确的账号密码, **Then** 系统返回 JWT Token 和用户基本信息
2. **Given** 用户状态为禁用, **When** 尝试登录, **Then** 系统拒绝并提示"账号已被禁用"
3. **Given** 用户已登录, **When** 调用退出接口, **Then** Token 失效，后续请求返回 401

---

### User Story 3 - 管理员管理用户状态 (Priority: P2)

管理员可以禁用、启用、锁定用户。被禁用的用户无法登录，被锁定的用户需要管理员解锁。

**Why this priority**: 用户状态管理是安全运维的基本能力，但不影响核心功能验证。

**Independent Test**: 禁用用户后该用户无法登录，启用后恢复正常。

**Acceptance Scenarios**:

1. **Given** 用户状态为正常, **When** 管理员点击"禁用", **Then** 用户状态变为禁用，该用户无法登录
2. **Given** 用户连续输错密码 5 次, **When** 第 6 次尝试, **Then** 账号自动锁定，提示联系管理员

---

### User Story 4 - 用户列表查询与筛选 (Priority: P3)

管理员在后台查看所有用户列表，支持按用户名、姓名、部门、状态筛选和分页。

**Why this priority**: 管理便利性功能，不影响核心业务流程。

**Independent Test**: 按部门筛选后只显示该部门用户，分页正确。

**Acceptance Scenarios**:

1. **Given** 系统中有多个用户, **When** 管理员打开用户列表页, **Then** 显示分页用户列表，默认按创建时间倒序
2. **Given** 管理员选择部门筛选条件, **When** 点击查询, **Then** 只显示该部门下的用户

---

### Edge Cases

- 删除部门时，该部门下的用户如何处理？（禁止删除非空部门）
- 用户同时绑定多个角色时，权限如何合并？（取并集）
- Token 过期时前端如何处理？（返回 401，前端跳转登录页）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持用户的增删改查（CRUD）操作
- **FR-002**: 系统 MUST 支持用户登录并颁发 JWT Token
- **FR-003**: 系统 MUST 支持用户退出登录并使 Token 失效
- **FR-004**: 系统 MUST 支持用户绑定一个部门和多个角色
- **FR-005**: 系统 MUST 支持用户状态管理（正常、禁用、锁定）
- **FR-006**: 系统 MUST 记录用户创建时间、更新时间、最后登录时间
- **FR-007**: 系统 MUST 支持按用户名、姓名、部门、状态筛选用户列表
- **FR-008**: 系统 MUST 对用户密码加密存储，禁止明文
- **FR-009**: 系统 MUST 在连续登录失败达到阈值时自动锁定账号

### Key Entities

- **sys_user**: 平台用户，包含 id、username、real_name、email、department_id、status（正常/禁用/锁定）、created_at、updated_at
- **sys_role**: 系统角色（普通员工、数据分析师、数据管理员、安全管理员、超级管理员），包含 id、role_code、role_name、description、status
- **sys_department**: 组织部门，包含 id、parent_id、dept_name、dept_code
- **sys_user_role**: 用户-角色多对多关联，包含 id、user_id、role_id
- **sys_permission**: 功能权限点，包含 id、permission_code、permission_name、module、description
- **sys_role_permission**: 角色-权限关联，包含 id、role_id、permission_id

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 管理员能在 30 秒内完成一个新用户的创建和角色分配
- **SC-002**: 用户登录响应时间不超过 2 秒
- **SC-003**: 系统支持至少 500 个用户账号的管理
- **SC-004**: 被禁用的用户 100% 无法通过任何方式登录系统

## Assumptions

- 系统采用 Spring Security + JWT 鉴权，Token 有效期由配置决定
- JWT 黑名单存储在 Redis 中（退出登录时写入）
- MVP 阶段不支持第三方 SSO 登录，仅支持账号密码
- 用户密码使用 BCrypt 加密存储
- 角色预定义 5 种：普通员工、数据分析师、数据管理员、安全管理员、超级管理员
- 系统保留至少一个超级管理员账号不可删除
- ORM 使用 MyBatis-Plus，数据库为 MySQL 8（平台管理库）

## Clarifications

### Session 2026-05-16

- Q: 角色有哪些预定义类型？ → A: 普通员工、数据分析师、数据管理员、安全管理员、超级管理员（文档第 24.1 节）
- Q: 鉴权技术方案？ → A: Spring Security + JWT，Redis 存黑名单（文档第 25 节）
- Q: 数据库表结构？ → A: sys_user、sys_role、sys_department、sys_user_role、sys_permission、sys_role_permission（文档第 23.1 节）
