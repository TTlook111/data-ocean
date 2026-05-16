# Tasks: 数据源管理模块

**Input**: Design documents from `specs/002-datasource-management/`
**Prerequisites**: plan.md, spec.md, data-model.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V3__create_datasource_tables.sql`，包含 datasource、datasource_secret、datasource_access、datasource_health_check 四张表的 DDL，含唯一索引 uk_host_port_db 和逻辑删除字段
- [ ] T002 在 `backend/pom.xml` 中添加 Jasypt（AES-256-GCM 加密）依赖

## Phase 2: Foundational — 实体与 Mapper

- [ ] T003 [P] 创建数据源实体类 `backend/src/main/java/com/dataocean/module/datasource/entity/Datasource.java`，映射 datasource 表，含逻辑删除和自动填充
- [ ] T004 [P] 创建密钥实体类 `backend/src/main/java/com/dataocean/module/datasource/entity/DatasourceSecret.java`，映射 datasource_secret 表
- [ ] T005 [P] 创建访问权限实体类 `backend/src/main/java/com/dataocean/module/datasource/entity/DatasourceAccess.java`，映射 datasource_access 表
- [ ] T006 [P] 创建健康检查实体类 `backend/src/main/java/com/dataocean/module/datasource/entity/DatasourceHealthCheck.java`，映射 datasource_health_check 表
- [ ] T007 [P] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/datasource/mapper/DatasourceMapper.java`、`DatasourceSecretMapper.java`、`DatasourceAccessMapper.java`、`DatasourceHealthCheckMapper.java`

## Phase 3: User Story 1 (P1) — 管理员添加数据源

**Goal**: 管理员配置 MySQL 数据源，测试连通性后保存，密码 AES-256 加密存储
**Independent Test**: 添加数据源后，系统能成功连接该数据库并返回连通性测试结果

- [ ] T008 [US1] 创建 AES-256-GCM 加解密服务 `backend/src/main/java/com/dataocean/module/datasource/service/DatasourceSecretService.java`，实现 encrypt(plainPassword) 和 decrypt(encryptedPassword) 方法，加密密钥从环境变量 `DATASOURCE_ENCRYPT_KEY` 读取
- [ ] T009 [US1] 创建连接测试服务 `backend/src/main/java/com/dataocean/module/datasource/service/DatasourceConnectionService.java`，实现 testConnection(host, port, dbName, username, password) 方法：创建临时 JDBC 连接执行 SELECT 1，超时 5 秒，返回成功/失败和响应时间
- [ ] T010 [US1] 创建数据源创建请求 DTO `backend/src/main/java/com/dataocean/module/datasource/dto/DatasourceCreateRequest.java`，字段：name、description、host、port、databaseName、charset、username、password，含 JSR-303 校验
- [ ] T011 [US1] 创建数据源更新请求 DTO `backend/src/main/java/com/dataocean/module/datasource/dto/DatasourceUpdateRequest.java`
- [ ] T012 [US1] 创建连接测试请求 DTO `backend/src/main/java/com/dataocean/module/datasource/dto/DatasourceTestRequest.java`，字段：host、port、databaseName、username、password
- [ ] T013 [US1] 创建数据源视图对象 `backend/src/main/java/com/dataocean/module/datasource/dto/DatasourceVO.java`，字段：id、name、description、dbType、host、port、databaseName、status、creatorName、lastCheckStatus、lastCheckTime、createdAt
- [ ] T014 [US1] 创建数据源核心服务 `backend/src/main/java/com/dataocean/module/datasource/service/DatasourceService.java`，实现：createDatasource（先测试连接，通过后保存主表 + 加密密码存 secret 表，失败则抛异常阻止保存）、updateDatasource、deleteDatasource（软删除，检查是否有 PUBLISHED 快照）、getDatasourceById、listDatasources
- [ ] T015 [US1] 创建管理端控制器 `backend/src/main/java/com/dataocean/module/datasource/controller/DatasourceAdminController.java`，实现：POST /api/admin/datasources（创建）、PUT /api/admin/datasources/{id}（更新）、DELETE /api/admin/datasources/{id}（删除）、GET /api/admin/datasources/{id}（详情）、GET /api/admin/datasources（列表）、POST /api/admin/datasources/test-connection（测试连接）
- [ ] T016 [US1] 在 DatasourceConnectionService 中实现健康检查记录存储：每次测试连接后将结果写入 datasource_health_check 表

## Phase 4: User Story 2 (P2) — 数据源状态管理

**Goal**: 管理员启用/禁用数据源，禁用后前台不可见且连接池销毁
**Independent Test**: 禁用数据源后，前台用户的数据源下拉列表中不再显示该数据源

- [ ] T017 [US2] 在 DatasourceService 中添加 enableDatasource(id) 和 disableDatasource(id) 方法，禁用时记录日志
- [ ] T018 [US2] 在 DatasourceAdminController 中添加 PUT /api/admin/datasources/{id}/status 接口，接收 status 参数（1=启用, 0=禁用）
- [ ] T019 [US2] 实现重复数据源检测逻辑：在 createDatasource 中校验 host+port+databaseName 组合唯一性（排除已删除记录）

## Phase 5: User Story 3 (P1) — 用户端数据源选择

**Goal**: 业务用户只能看到自己有权限的已启用数据源
**Independent Test**: 用户只能看到自己有权限的已启用数据源

- [ ] T020 [US3] 创建数据源精简视图 `backend/src/main/java/com/dataocean/module/datasource/dto/DatasourceSimpleVO.java`，字段：id、name、databaseName（仅用户端需要的信息）
- [ ] T021 [US3] 创建访问权限服务 `backend/src/main/java/com/dataocean/module/datasource/service/DatasourceAccessService.java`，实现：grantAccess(datasourceId, userId, grantedBy)、revokeAccess(datasourceId, userId)、listAccessibleDatasources(userId)（返回用户有权限且状态为启用的数据源列表）、checkAccess(datasourceId, userId)
- [ ] T022 [US3] 创建用户端控制器 `backend/src/main/java/com/dataocean/module/datasource/controller/DatasourceUserController.java`，实现 GET /api/datasources/mine 接口（返回当前登录用户有权限的已启用数据源列表）
- [ ] T023 [US3] 在 DatasourceAdminController 中添加权限管理接口：POST /api/admin/datasources/{id}/access（授权）、DELETE /api/admin/datasources/{id}/access/{userId}（撤销）、GET /api/admin/datasources/{id}/access（查看授权列表）

## Phase 6: Polish & Cross-Cutting

- [ ] T024 确保 DatasourceSecretService 的日志中不输出明文密码，加密/解密方法中对入参做非空校验
- [ ] T025 在 DatasourceService.deleteDatasource 中实现删除约束检查：有 PUBLISHED 快照或关联 skills.md 的数据源禁止删除，返回明确错误信息

## Dependencies

```
T001 → T003-T007 (表结构先于实体)
T002 → T008 (加密依赖先于加密服务)
T003-T007 → T014 (实体/Mapper 先于 Service)
T008, T009 → T014 (加密和连接服务先于核心 Service)
T014 → T015 (Service 先于 Controller)
T014 → T017-T019 (核心 CRUD 先于状态管理)
T014 → T021 (核心 Service 先于权限 Service)
T021 → T022-T023 (权限 Service 先于用户端 Controller)
```

## Implementation Strategy

MVP-first: Phase 3（数据源 CRUD + 加密 + 连接测试）是核心，确保管理员能安全地添加和测试数据源。Phase 5（用户端选择）与 Phase 4（状态管理）可并行开发。加密服务和连接测试服务作为独立组件优先实现，被核心 Service 组合调用。
