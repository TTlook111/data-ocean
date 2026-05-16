# Implementation Plan: 用户模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-user-module/spec.md`

## Summary

用户模块是 DataOcean 平台的基础设施层，提供用户 CRUD、JWT 鉴权、角色绑定和状态管理。技术方案采用 Spring Boot 3.x + Spring Security + JWT + MyBatis-Plus + Redis，数据库为独立的 MySQL 8 平台管理库。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, Spring Security, MyBatis-Plus, jjwt (JWT), BCrypt, Redis (Lettuce)

**Storage**: MySQL 8 (平台管理库，独立于业务库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL + Redis)

**Target Platform**: Linux server (Docker)

**Project Type**: Web service (REST API)

**Performance Goals**: 登录响应 < 2s, 用户列表查询 < 1s

**Constraints**: 500+ 用户管理, JWT 黑名单需 Redis

**Scale/Scope**: MVP 阶段，单实例部署

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | N/A | 用户模块不涉及元数据 |
| II. SQL 安全与只读执行 | N/A | 用户模块操作管理库，非业务库 |
| III. 三层分离架构 | ✅ PASS | 用户模块纯 Java 层，前端调 Java API |
| IV. RAG 准入控制 | N/A | 用户模块不涉及 RAG |
| V. 可信度驱动生成 | N/A | 用户模块不涉及可信度 |
| VI. 渐进式 MVP | ✅ PASS | 仅实现账号密码登录，不做 SSO |

**Gate Result**: PASS — 无违规项

## Project Structure

### Source Code (repository root)

```text
backend/
├── pom.xml
├── src/main/java/com/dataocean/
│   ├── DataOceanApplication.java
│   ├── common/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   └── MyBatisPlusConfig.java
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   └── BusinessException.java
│   │   ├── result/
│   │   │   └── Result.java
│   │   └── security/
│   │       ├── JwtTokenProvider.java
│   │       ├── JwtAuthenticationFilter.java
│   │       └── UserDetailsServiceImpl.java
│   └── module/
│       └── user/
│           ├── controller/
│           │   ├── AuthController.java
│           │   ├── UserController.java
│           │   ├── RoleController.java
│           │   └── DepartmentController.java
│           ├── service/
│           │   ├── AuthService.java
│           │   ├── UserService.java
│           │   ├── RoleService.java
│           │   └── DepartmentService.java
│           ├── mapper/
│           │   ├── UserMapper.java
│           │   ├── RoleMapper.java
│           │   ├── DepartmentMapper.java
│           │   ├── UserRoleMapper.java
│           │   └── RolePermissionMapper.java
│           ├── entity/
│           │   ├── SysUser.java
│           │   ├── SysRole.java
│           │   ├── SysDepartment.java
│           │   ├── SysUserRole.java
│           │   ├── SysPermission.java
│           │   └── SysRolePermission.java
│           └── dto/
│               ├── LoginRequest.java
│               ├── LoginResponse.java
│               ├── UserCreateRequest.java
│               ├── UserUpdateRequest.java
│               ├── UserQueryRequest.java
│               └── UserVO.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── db/migration/
│       ├── V1__create_user_tables.sql
│       └── V2__init_roles_and_admin.sql
└── src/test/java/com/dataocean/module/user/
    ├── controller/AuthControllerTest.java
    └── service/UserServiceTest.java
```

**Structure Decision**: 采用单模块 Maven 项目 + 按业务模块分包（module/user, module/datasource...），后续模块在 module/ 下平级扩展。

## Complexity Tracking

无违规项，无需记录。
