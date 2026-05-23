# 001 用户模块

## 概述
用户模块是系统的基础鉴权模块，提供用户登录认证、JWT 令牌管理、用户/角色/部门的 CRUD 管理功能。所有其他模块的权限控制都依赖此模块提供的认证和授权基础设施。

## 解决的问题
- 用户身份认证（登录/登出/JWT 签发与校验）
- 登录安全（失败次数限制、账户锁定、验证码）
- 首次登录强制改密
- 用户生命周期管理（创建/编辑/禁用/删除）
- 基于角色的权限控制（RBAC）
- 部门组织架构管理（树形结构）
- JWT 即时失效（黑名单 + 令牌版本号机制）

## 实现方案
- **认证**: Spring Security + JWT，令牌中携带 userId、roles、permissions
- **JWT 失效**: Redis 黑名单（退出登录）+ 令牌版本号（强制下线/改密/禁用）
- **密码安全**: BCrypt 加密存储，重置时生成随机临时密码（排除易混淆字符）
- **登录限流**: Redis 计数器，5 次失败后锁定账户
- **权限模型**: 用户 → 角色 → 权限 三级关联，Controller 层 @PreAuthorize 注解控制
- **部门树**: parent_id 自关联，内存中组装树形结构

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/user/controller/`
  - `AuthController.java` — 认证相关（登录/登出/改密/个人资料）
  - `UserController.java` — 用户管理 CRUD
  - `RoleController.java` — 角色列表查询
  - `DepartmentController.java` — 部门树管理
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/user/service/`
  - `AuthService.java` / `impl/AuthServiceImpl.java`
  - `UserService.java` / `impl/UserServiceImpl.java`
  - `RoleService.java` / `impl/RoleServiceImpl.java`
  - `DepartmentService.java` / `impl/DepartmentServiceImpl.java`
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/user/entity/`
  - `SysUser.java`, `SysRole.java`, `SysDepartment.java`, `SysPermission.java`, `SysUserRole.java`, `SysRolePermission.java`
- Security: `backend/DataOcean/src/main/java/com/dataocean/common/security/`
  - `JwtTokenProvider.java` — JWT 生成/解析/验证
  - `JwtAuthenticationFilter.java` — 请求拦截认证
  - `UserDetailsServiceImpl.java` — 用户详情加载
  - `SecurityConfig.java` — Spring Security 配置
  - `LoginUser.java` — 认证用户信息封装
  - `UserContext.java` — 当前用户上下文工具类

### 前端
- 页面: `frontend/src/views/`
  - `login/LoginPage.vue` — 登录页
  - `profile/ChangePassword.vue` — 修改密码
  - `profile/ProfileView.vue` — 个人资料
  - `admin/user/UserList.vue` — 用户管理
  - `admin/user/RoleList.vue` — 角色管理
  - `admin/user/DepartmentTree.vue` — 部门管理
- API: `frontend/src/api/admin/user.ts`

### 数据库
- 迁移脚本: `V1__create_user_tables.sql`, `V2__init_roles_and_admin.sql`, `V3__add_password_changed_to_user.sql`
- 涉及表: `sys_user`, `sys_role`, `sys_department`, `sys_permission`, `sys_user_role`, `sys_role_permission`

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| POST | `/api/auth/login` | 用户登录 | 前端登录页 |
| POST | `/api/auth/logout` | 用户登出 | 前端顶部菜单 |
| GET | `/api/auth/me` | 获取当前用户信息 | 前端路由守卫 |
| PUT | `/api/auth/password` | 修改密码 | 前端改密页 |
| PUT | `/api/auth/profile` | 更新个人资料 | 前端个人资料页 |
| GET | `/api/admin/users` | 用户列表 | 前端用户管理页 |
| POST | `/api/admin/users` | 创建用户 | 前端用户管理页 |
| PUT | `/api/admin/users/{id}` | 更新用户 | 前端用户管理页 |
| PATCH | `/api/admin/users/{id}/status` | 更新状态 | 前端用户管理页 |
| DELETE | `/api/admin/users/{id}` | 删除用户 | 前端用户管理页 |
| POST | `/api/admin/users/{id}/reset-password` | 重置密码 | 前端用户管理页 |
| GET | `/api/admin/roles` | 角色列表 | 前端角色选择下拉 |
| GET | `/api/admin/departments/tree` | 部门树 | 前端部门管理/用户表单 |
| POST | `/api/admin/departments` | 创建部门 | 前端部门管理页 |
| DELETE | `/api/admin/departments/{id}` | 删除部门 | 前端部门管理页 |

## 模块间依赖
- **被依赖**: 所有模块都依赖此模块的认证和权限基础设施（JWT 过滤器、@PreAuthorize、UserContext）
- **依赖**: Redis（JWT 黑名单、登录失败计数、令牌版本号）
