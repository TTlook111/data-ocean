# Tasks: 权限与安全模块

**Input**: Design documents from `specs/015-permission-security/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V11__create_permission_tables.sql`，建表 datasource_access（id, subject_type, subject_id, datasource_id, can_query, can_export, can_view_sql, created_at, updated_at）和 datasource_access_policy（id, datasource_id, subject_type, subject_id, table_name, column_name, access_type, row_filter_expression, mask_strategy, created_at, updated_at）
- [ ] T002 创建 Java 包结构目录 `backend/src/main/java/com/dataocean/module/permission/`，包含 controller/, service/, mapper/, entity/, dto/, enums/ 子包
- [ ] T003 [P] 创建枚举类 `backend/src/main/java/com/dataocean/module/permission/enums/SubjectType.java`，定义 USER, ROLE, DEPARTMENT
- [ ] T004 [P] 创建枚举类 `backend/src/main/java/com/dataocean/module/permission/enums/MaskStrategy.java`，定义 PHONE, ID_CARD, EMAIL, BANK_CARD, NAME 及各自的脱敏规则描述

## Phase 2: User Story 1 (P1) — 数据源级访问控制

**Goal**: 用户未被授权的数据源不出现在前台下拉列表中
**Independent Test**: 未授权数据源的 API 调用返回 403

- [ ] T005 [P] [US1] 创建实体类 `backend/src/main/java/com/dataocean/module/permission/entity/DatasourceAccess.java`，MyBatis-Plus 注解映射 datasource_access 表
- [ ] T006 [P] [US1] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/permission/mapper/DatasourceAccessMapper.java`，继承 BaseMapper，增加自定义方法 selectBySubject(subjectType, subjectId)
- [ ] T007 [P] [US1] 创建 DTO `backend/src/main/java/com/dataocean/module/permission/dto/DatasourceAccessVO.java`，包含授权信息展示字段
- [ ] T008 [US1] 创建 Service `backend/src/main/java/com/dataocean/module/permission/service/DatasourceAccessService.java`，实现授权 CRUD：grant(subjectType, subjectId, datasourceId, permissions)、revoke、listBySubject、listByDatasource、checkAccess(userId, datasourceId) 返回 boolean
- [ ] T009 [US1] 创建 Controller `backend/src/main/java/com/dataocean/module/permission/controller/DatasourceAccessController.java`，实现 POST /api/admin/datasource-access（授权）、DELETE（撤销）、GET /api/admin/datasource-access?subjectType=&subjectId=（查询授权列表）
- [ ] T010 [US1] 创建数据源权限拦截器：在查询接口（POST /api/query/ask）中校验当前用户对目标数据源的 can_query 权限，无权限返回 403；在数据源列表接口中过滤只返回已授权的数据源

## Phase 3: User Story 2 (P1) — 行列级策略配置

**Goal**: 配置行级过滤后，用户查询结果只包含过滤后的数据
**Independent Test**: SQL 自动追加 WHERE 条件，禁止列被移除

- [ ] T011 [P] [US2] 创建实体类 `backend/src/main/java/com/dataocean/module/permission/entity/DatasourceAccessPolicy.java`，MyBatis-Plus 注解映射 datasource_access_policy 表
- [ ] T012 [P] [US2] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/permission/mapper/DatasourceAccessPolicyMapper.java`，继承 BaseMapper，增加自定义方法 selectPolicies(datasourceId, subjectType, subjectId)
- [ ] T013 [P] [US2] 创建 DTO `backend/src/main/java/com/dataocean/module/permission/dto/AccessPolicyVO.java` 和 `AccessPolicyCreateRequest.java`，包含策略配置字段
- [ ] T014 [US2] 创建 Service `backend/src/main/java/com/dataocean/module/permission/service/AccessPolicyService.java`，实现策略 CRUD：createPolicy、updatePolicy、deletePolicy、listPolicies(datasourceId, subjectType, subjectId)
- [ ] T015 [US2] 创建 PermissionCalculator `backend/src/main/java/com/dataocean/module/permission/service/PermissionCalculator.java`，实现多角色权限合并逻辑：allowedTables 取并集、deniedColumns 取交集（所有角色都禁止才禁止）、rowFilters 取 AND 合并、maskColumns 取并集
- [ ] T016 [US2] 创建 PermissionContext DTO `backend/src/main/java/com/dataocean/module/permission/dto/PermissionContext.java`，包含 allowedTables, deniedColumns, maskColumns, rowFilters 字段，作为传给 Python 的权限上下文 JSON
- [ ] T017 [US2] 创建 Controller `backend/src/main/java/com/dataocean/module/permission/controller/AccessPolicyController.java`，实现 POST /api/admin/access-policies（创建策略）、PUT（更新）、DELETE（删除）、GET（查询策略列表）

## Phase 4: Python AST 权限注入

- [ ] T018 [P] 创建 Python 权限注入模块 `python-service/dataocean/security/permission_injector.py`，实现 inject_permissions(sql: str, context: PermissionContext) 函数：使用 sqlglot 解析 SQL AST，从 SELECT 中移除 deniedColumns 对应的列，在 WHERE 中追加 rowFilters 条件（AND 连接）
- [ ] T019 [P] 创建 Python 输入清洗模块 `python-service/dataocean/security/input_sanitizer.py`，实现 sanitize(user_input: str) 函数：过滤危险指令词（ignore previous, system prompt, 忽略上述 等），返回清洗后的文本
- [ ] T020 将 permission_injector 集成到 LangGraph SQL_Validator_Node：SQL 生成后、执行前调用 inject_permissions，确保权限约束始终生效

## Phase 5: 结果脱敏

- [ ] T021 创建 DataMaskingService `backend/src/main/java/com/dataocean/module/permission/service/DataMaskingService.java`，实现 maskResult(queryResult, maskColumns) 方法：根据 MaskStrategy 枚举对指定列执行脱敏（PHONE: 138****5678, ID_CARD: 310***********1234, EMAIL: a**@example.com, BANK_CARD: ****1234, NAME: 张*）
- [ ] T022 在查询结果返回前端之前调用 DataMaskingService：在 Java 网关的查询结果处理逻辑中，根据 PermissionContext.maskColumns 对结果集执行脱敏

## Phase 6: User Story 3 (P2) — 角色与功能权限

**Goal**: 普通用户无法访问后台治理页面
**Independent Test**: 无后台权限的用户访问 /admin/* 返回 403

- [ ] T023 [US3] 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V11_1__init_roles_permissions.sql`，初始化 5 个预设角色（普通员工、数据分析师、数据管理员、安全管理员、超级管理员）和功能权限点（admin:view, datasource:manage, metadata:view, governance:manage, skills:manage, prompt:manage, field:manage, feedback:review, audit:view, user:manage, role:manage）
- [ ] T024 [US3] 实现超级管理员保护逻辑：在角色删除和用户角色变更接口中，校验系统至少保留一个超级管理员账号

## Phase 7: Polish & Cross-Cutting

- [ ] T025 实现权限变更审计：所有权限配置变更（授权、撤销、策略修改）记录到审计日志表
- [ ] T026 实现 governance_status 联动：表的 governance_status 为 BLOCKED 时自动加入 deniedTables，无需额外配置权限
- [ ] T027 实现结果级控制：根据 can_export 控制前端导出按钮显示，根据 can_view_sql 控制是否返回完整 SQL 文本

## Dependencies

```
T001 → T005, T006, T011, T012
T003, T004 → T005, T011, T021
T005, T006 → T008 → T009, T010
T011, T012 → T014 → T015 → T016
T016 → T018, T022
T014 → T017
T015 → T010 (拦截器需要 PermissionCalculator)
T018 → T020
T021 → T022
T023 → T024
```

## Implementation Strategy

MVP-first: Phase 1-2 实现数据源级访问控制（最基本的安全保障），Phase 3 实现行列级策略配置（Java 侧），Phase 4 实现 Python AST 注入（SQL 层强制执行），Phase 5 实现结果脱敏。Java 和 Python 可并行开发（Phase 2-3 与 Phase 4 并行），通过 PermissionContext JSON 契约对接。
