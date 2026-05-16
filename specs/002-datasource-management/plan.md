# Implementation Plan: 数据源管理模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-datasource-management/spec.md`

## Summary

数据源管理模块负责 MySQL 数据源的 CRUD、连通性测试、AES-256 密码加密、连接池配置和权限过滤。纯 Java 层实现，是元数据采集和 NL2SQL 查询的前置基础。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, HikariCP, Jasypt (AES-256), MySQL Connector/J

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc + Testcontainers (MySQL)

**Target Platform**: Linux server (Docker)

**Project Type**: Web service (REST API)

**Performance Goals**: 连接测试响应 < 5s, 数据源列表查询 < 500ms

**Constraints**: 密码必须加密存储, 只读账号强制校验

**Scale/Scope**: MVP 阶段，支持 50+ 数据源管理

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | 数据源是元数据采集的前置条件 |
| II. SQL 安全与只读执行 | ✅ PASS | 强制只读账号，连接测试仅 SELECT 1 |
| III. 三层分离架构 | ✅ PASS | 纯 Java 层，前端调 Java API |
| IV. RAG 准入控制 | N/A | 数据源模块不直接涉及 RAG |
| V. 可信度驱动生成 | N/A | 不涉及 |
| VI. 渐进式 MVP | ✅ PASS | 仅支持 MySQL，不做多数据库类型 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/datasource/
├── controller/
│   ├── DatasourceAdminController.java      # 管理端 CRUD + 测试连接
│   └── DatasourceUserController.java       # 用户端查询（权限过滤）
├── service/
│   ├── DatasourceService.java
│   ├── DatasourceSecretService.java        # 密码加解密
│   ├── DatasourceConnectionService.java    # 连接测试 + 连接池管理
│   └── DatasourceAccessService.java        # 权限管理
├── mapper/
│   ├── DatasourceMapper.java
│   ├── DatasourceSecretMapper.java
│   ├── DatasourceAccessMapper.java
│   └── DatasourceHealthCheckMapper.java
├── entity/
│   ├── Datasource.java
│   ├── DatasourceSecret.java
│   ├── DatasourceAccess.java
│   └── DatasourceHealthCheck.java
└── dto/
    ├── DatasourceCreateRequest.java
    ├── DatasourceUpdateRequest.java
    ├── DatasourceTestRequest.java
    ├── DatasourceVO.java
    └── DatasourceSimpleVO.java             # 用户端精简视图

backend/src/main/resources/db/migration/
└── V3__create_datasource_tables.sql
```

## Implementation Phases

### Phase 1: 核心 CRUD + 密码加密

1. 创建数据库表（Flyway V3）
2. 实现 AES-256 加解密工具类（基于 Jasypt）
3. 实现 Datasource 实体和 Mapper
4. 实现 DatasourceService（增删改查）
5. 实现 DatasourceAdminController

### Phase 2: 连接测试 + 健康检查

1. 实现 DatasourceConnectionService（SELECT 1 测试）
2. 连接超时控制（5s）
3. 健康检查记录存储
4. 连接池动态创建/销毁

### Phase 3: 权限过滤 + 用户端接口

1. 实现 DatasourceAccessService（用户-数据源权限绑定）
2. 实现 DatasourceUserController（仅返回有权限的已启用数据源）
3. 禁用数据源时级联处理

### Phase 4: 测试

1. 单元测试（Service 层）
2. 集成测试（Controller + Testcontainers）
3. 加密/解密正确性验证

## Complexity Tracking

无违规项。
