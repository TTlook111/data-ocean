# Implementation Plan: 权限与安全模块

**Branch**: `015-permission-security` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

权限与安全模块实现多层数据访问控制：数据源级 → 表级 → 列级 → 行级 → 结果级。Java 层负责权限配置管理、权限计算和结果脱敏，Python 层在 SQL AST 上强制执行列级禁止和行级过滤。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x) + Python 3.13

**Primary Dependencies**:
- Java: Spring Security, MyBatis-Plus, Flyway
- Python: sqlglot (SQL AST 操作)

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc (Java), pytest (Python AST 注入测试)

**Constraints**: 权限变更 5 秒内生效；脱敏在 Java 层执行；AST 注入不依赖 Prompt

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | PASS | 表级权限与 governance_status 联动 |
| II. SQL 安全与只读执行 | PASS | AST 层强制执行权限，不依赖 Prompt |
| III. 三层分离架构 | PASS | Java 管理权限，Python 执行 AST 注入 |
| IV. RAG 准入控制 | PASS | denied columns 不进入 RAG 召回 |
| V. 可信度驱动生成 | N/A | 权限模块不涉及可信度 |
| VI. 渐进式 MVP | PASS | 5 个角色，不做动态 RBAC |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/permission/
├── controller/
│   ├── DatasourceAccessController.java
│   └── AccessPolicyController.java
├── service/
│   ├── DatasourceAccessService.java
│   ├── AccessPolicyService.java
│   ├── PermissionCalculator.java      # 权限合并计算
│   └── DataMaskingService.java        # 结果脱敏
├── mapper/
│   ├── DatasourceAccessMapper.java
│   └── DatasourceAccessPolicyMapper.java
├── entity/
│   ├── DatasourceAccess.java
│   └── DatasourceAccessPolicy.java
├── dto/
│   ├── DatasourceAccessVO.java
│   ├── AccessPolicyVO.java
│   ├── AccessPolicyCreateRequest.java
│   └── PermissionContext.java         # 传给 Python 的权限上下文
└── enums/
    ├── SubjectType.java               # USER/ROLE/DEPARTMENT
    └── MaskStrategy.java              # PHONE/ID_CARD/EMAIL/BANK_CARD/NAME

backend/src/main/resources/db/migration/
└── V11__create_permission_tables.sql

python-service/dataocean/security/
├── permission_injector.py             # SQL AST 权限注入
└── input_sanitizer.py                 # Prompt 注入防护预处理
```

## Implementation Phases

### Phase 1: 数据源级访问控制

1. Flyway 迁移创建 datasource_access 表
2. 实现授权 CRUD（为用户/角色/部门配置数据源访问权限）
3. 查询接口增加数据源权限校验拦截器
4. 前端数据源下拉列表只展示已授权的数据源

### Phase 2: 行列级策略配置

1. Flyway 迁移创建 datasource_access_policy 表
2. 实现策略 CRUD（表级、列级、行级配置）
3. 实现 PermissionCalculator：多角色权限合并（并集策略）
4. 生成 PermissionContext JSON 传给 Python

### Phase 3: Python AST 权限注入

1. 实现 permission_injector.py：
   - 列级禁止：从 SELECT 中移除 denied columns
   - 行级过滤：WHERE 追加 AND 条件
2. 集成到 SQL_Validator_Node（LangGraph 节点）

### Phase 4: 结果脱敏

1. 实现 DataMaskingService（Java 网关层）
2. 查询结果返回前，根据 maskColumns 配置对指定列执行脱敏
3. 脱敏规则：PHONE/ID_CARD/EMAIL/BANK_CARD/NAME

### Phase 5: Prompt 注入防护

1. 实现 input_sanitizer.py：过滤危险指令词
2. Prompt 模板中 Role 隔离（system/user message 分离）
3. AST 兜底：无论 SQL 内容如何，权限约束始终生效

## Key Design Decisions

- **权限合并**: 多角色取并集（最宽松），deniedColumns 取交集（所有角色都禁止才禁止）
- **脱敏层级**: Java 网关层执行，不在 SQL 层处理（避免影响聚合计算）
- **权限传递**: Java 每次请求计算并传给 Python，Python 无状态不缓存
- **AST 注入时机**: SQL 生成后、执行前，在 SQL_Validator_Node 中执行
- **governance_status 联动**: 表的 governance_status 为 BLOCKED 时自动禁止访问，无需额外配置
