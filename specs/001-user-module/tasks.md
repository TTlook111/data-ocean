# Tasks: 用户模块

**Input**: Design documents from `specs/001-user-module/`
**Prerequisites**: plan.md, spec.md, data-model.md

## Phase 1: Setup

- [ ] T001 创建 Maven 项目骨架 `backend/pom.xml`，引入 Spring Boot 3.x、Spring Security、MyBatis-Plus、jjwt、Lettuce (Redis)、MySQL Connector/J、Flyway、OpenFeign 依赖
- [ ] T002 创建 Spring Boot 主类 `backend/src/main/java/com/dataocean/DataOceanApplication.java`
- [ ] T003 创建应用配置文件 `backend/src/main/resources/application.yml`（数据源、Redis、JWT 密钥、Flyway、OpenFeign 配置）
- [ ] T004 创建开发环境配置 `backend/src/main/resources/application-dev.yml`
- [ ] T005 创建 `docker-compose.yml`（MySQL 8、Redis 7、Milvus 2.x standalone + etcd + minio），配置端口映射和数据卷
- [ ] T006 创建 Python 服务项目骨架 `python-service/pyproject.toml`（依赖：fastapi、uvicorn、langgraph、llama-index、sqlalchemy、aiomysql、sqlglot、dashscope、milvus、pydantic），创建 `python-service/dataocean/__init__.py` 和 `python-service/dataocean/main.py`（FastAPI app 实例，挂载各模块 router）
- [ ] T007 创建 `python-service/Dockerfile`（基于 python:3.13-slim，uv install，uvicorn 启动）
- [ ] T008 创建前端项目骨架：`cd frontend && npm create vite@latest . -- --template vue-ts`，安装 element-plus、vue-router、pinia、axios、echarts 依赖，创建基础目录结构（src/views/、src/components/、src/api/、src/stores/、src/router/）
- [ ] T009 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V1__create_user_tables.sql`，包含 sys_user、sys_role、sys_department、sys_user_role、sys_permission、sys_role_permission 六张表的 DDL
- [ ] T010 创建 Flyway 初始化数据脚本 `backend/src/main/resources/db/migration/V2__init_roles_and_admin.sql`，插入 5 个预定义角色和超级管理员账号

## Phase 2: Foundational

- [ ] T011 [P] 创建统一响应封装类 `backend/src/main/java/com/dataocean/common/result/Result.java`，包含 code、message、data 字段和静态工厂方法
- [ ] T012 [P] 创建业务异常类 `backend/src/main/java/com/dataocean/common/exception/BusinessException.java`，包含错误码和消息
- [ ] T013 [P] 创建全局异常处理器 `backend/src/main/java/com/dataocean/common/exception/GlobalExceptionHandler.java`，处理 BusinessException、MethodArgumentNotValidException、AuthenticationException
- [ ] T014 [P] 创建 MyBatis-Plus 配置类 `backend/src/main/java/com/dataocean/common/config/MyBatisPlusConfig.java`，配置分页插件和逻辑删除
- [ ] T015 [P] 创建 Redis 配置类 `backend/src/main/java/com/dataocean/common/config/RedisConfig.java`，配置 RedisTemplate 序列化

## Phase 3: User Story 2 (P1) — JWT 鉴权

**Goal**: 用户通过账号密码登录系统，系统颁发 Token，退出登录时 Token 失效
**Independent Test**: 用户登录后能访问受保护接口，退出后再访问返回 401

- [ ] T024 [US2] 创建 JWT 工具类 `backend/src/main/java/com/dataocean/common/security/JwtTokenProvider.java`，实现 generateToken、validateToken、getUsernameFromToken、getExpiration 方法，Token 有效期从配置读取
- [ ] T021 [US2] 创建 JWT 认证过滤器 `backend/src/main/java/com/dataocean/common/security/JwtAuthenticationFilter.java`，从 Authorization Header 提取 Token，校验有效性并设置 SecurityContext，同时检查 Redis 黑名单
- [ ] T022 [US2] 创建 UserDetailsService 实现 `backend/src/main/java/com/dataocean/common/security/UserDetailsServiceImpl.java`，从数据库加载用户信息和权限列表
- [ ] T023 [US2] 创建 Spring Security 配置类 `backend/src/main/java/com/dataocean/common/config/SecurityConfig.java`，配置无状态会话、JWT 过滤器链、公开路径（/api/auth/login）、其余路径需认证
- [ ] T024 [US2] 创建登录请求 DTO `backend/src/main/java/com/dataocean/module/user/dto/LoginRequest.java`，字段：username（@NotBlank）、password（@NotBlank）
- [ ] T021 [US2] 创建登录响应 DTO `backend/src/main/java/com/dataocean/module/user/dto/LoginResponse.java`，字段：token、tokenType、expiresIn、userId、username、realName、roles
- [ ] T022 [US2] 创建认证服务 `backend/src/main/java/com/dataocean/module/user/service/AuthService.java`，实现 login（验证密码、检查状态、生成 Token、更新最后登录时间）和 logout（Token 加入 Redis 黑名单）方法
- [ ] T023 [US2] 创建认证控制器 `backend/src/main/java/com/dataocean/module/user/controller/AuthController.java`，POST /api/auth/login 和 POST /api/auth/logout 接口
- [ ] T024 [US2] 实现登录失败计数逻辑：在 AuthService.login 中，连续失败 5 次自动锁定账号（失败次数存 Redis，成功后清零）

## Phase 4: User Story 1 (P1) — 用户 CRUD 与角色分配

**Goal**: 管理员创建用户并分配角色，使该用户能登录系统
**Independent Test**: 创建用户后，该用户能成功登录系统并看到其角色对应的功能菜单

- [ ] T021 [P] [US1] 创建用户实体类 `backend/src/main/java/com/dataocean/module/user/entity/SysUser.java`，使用 MyBatis-Plus 注解映射 sys_user 表，包含逻辑删除和自动填充配置
- [ ] T022 [P] [US1] 创建角色实体类 `backend/src/main/java/com/dataocean/module/user/entity/SysRole.java`
- [ ] T023 [P] [US1] 创建部门实体类 `backend/src/main/java/com/dataocean/module/user/entity/SysDepartment.java`
- [ ] T024 [P] [US1] 创建关联实体类 `backend/src/main/java/com/dataocean/module/user/entity/SysUserRole.java`、`SysPermission.java`、`SysRolePermission.java`
- [ ] T025 [P] [US1] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/user/mapper/UserMapper.java`、`RoleMapper.java`、`DepartmentMapper.java`、`UserRoleMapper.java`、`RolePermissionMapper.java`
- [ ] T026 [US1] 创建用户创建请求 DTO `backend/src/main/java/com/dataocean/module/user/dto/UserCreateRequest.java`，字段：username、password、realName、email、phone、departmentId、roleIds，含 JSR-303 校验注解
- [ ] T027 [US1] 创建用户更新请求 DTO `backend/src/main/java/com/dataocean/module/user/dto/UserUpdateRequest.java`，字段：realName、email、phone、departmentId、roleIds
- [ ] T028 [US1] 创建用户视图对象 `backend/src/main/java/com/dataocean/module/user/dto/UserVO.java`，字段：id、username、realName、email、phone、departmentName、roleNames、status、lastLoginAt、createdAt
- [ ] T029 [US1] 创建用户服务 `backend/src/main/java/com/dataocean/module/user/service/UserService.java`，实现 createUser（校验用户名唯一、BCrypt 加密密码、保存用户、绑定角色）、updateUser、deleteUser（逻辑删除，超级管理员不可删）、getUserById
- [ ] T030 [US1] 创建用户管理控制器 `backend/src/main/java/com/dataocean/module/user/controller/UserController.java`，实现 POST /api/admin/users、PUT /api/admin/users/{id}、DELETE /api/admin/users/{id}、GET /api/admin/users/{id} 接口，需 @PreAuthorize("hasAuthority('user:manage')")

## Phase 5: User Story 3 (P2) — 用户状态管理

**Goal**: 管理员可以禁用、启用、锁定用户
**Independent Test**: 禁用用户后该用户无法登录，启用后恢复正常

- [ ] T031 [US3] 在 UserService 中添加 disableUser、enableUser、unlockUser 方法，包含状态流转校验（NORMAL↔DISABLED, LOCKED→NORMAL）
- [ ] T032 [US3] 在 UserController 中添加 PUT /api/admin/users/{id}/status 接口，接收目标状态参数，调用对应 Service 方法
- [ ] T033 [US3] 禁用用户时清除该用户所有有效 Token（遍历 Redis 或记录用户 Token 列表）

## Phase 6: User Story 4 (P3) — 用户列表查询

**Goal**: 管理员查看所有用户列表，支持筛选和分页
**Independent Test**: 按部门筛选后只显示该部门用户，分页正确

- [ ] T034 [US4] 创建查询请求 DTO `backend/src/main/java/com/dataocean/module/user/dto/UserQueryRequest.java`，字段：username、realName、departmentId、status、pageNum（默认1）、pageSize（默认20）
- [ ] T035 [US4] 在 UserService 中实现 listUsers 方法，使用 MyBatis-Plus 条件构造器实现多条件筛选 + 分页，默认按 created_at DESC 排序
- [ ] T036 [US4] 在 UserController 中添加 GET /api/admin/users 接口，接收 UserQueryRequest 参数，返回分页结果

## Phase 7: 角色与部门管理

- [ ] T037 [P] 创建角色服务 `backend/src/main/java/com/dataocean/module/user/service/RoleService.java`，实现角色列表查询（供下拉选择用）
- [ ] T038 [P] 创建部门服务 `backend/src/main/java/com/dataocean/module/user/service/DepartmentService.java`，实现部门树查询、创建部门、删除部门（非空部门禁止删除）
- [ ] T039 [P] 创建角色控制器 `backend/src/main/java/com/dataocean/module/user/controller/RoleController.java`，GET /api/roles 接口
- [ ] T040 [P] 创建部门控制器 `backend/src/main/java/com/dataocean/module/user/controller/DepartmentController.java`，GET /api/departments/tree、POST /api/departments、DELETE /api/departments/{id} 接口

## Dependencies

```
T001 → T002, T003, T004
T005, T006 → T021-T025 (实体和 Mapper 依赖表结构)
T007-T011 → T012-T020 (基础设施先于业务)
T012-T015 → T018-T020 (Security 框架先于认证逻辑)
T021-T025 → T029-T030 (实体先于 Service/Controller)
T029 → T031-T033 (用户 CRUD 先于状态管理)
T029 → T035-T036 (用户 CRUD 先于列表查询)
```

## Implementation Strategy

MVP-first: 先完成 JWT 鉴权（US2）确保安全基础可用，再实现用户 CRUD（US1），最后补充状态管理（US3）和列表查询（US4）。角色和部门管理作为辅助功能并行开发。所有 Phase 2 的基础设施任务可并行执行。
