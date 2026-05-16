# Research: 权限与安全模块

## 行列级权限实现方案

**Decision**: SQL AST 层注入，使用 sqlglot (Python) 在 SQL 生成后、执行前强制注入权限约束

**Rationale**: AST 层注入不依赖 Prompt（Prompt 可被绕过），在 SQL 解析树上直接操作，100% 可靠。sqlglot 支持 MySQL 方言，能精确识别 SELECT 的列和 WHERE 条件。

**Implementation**:
- 列级禁止: 从 AST 的 SELECT 列表中移除 denied columns，若 SELECT * 则展开为具体列再移除
- 列级脱敏: 不在 SQL 层处理，在 Java 网关返回结果时对指定列执行脱敏
- 行级过滤: 在 AST 的 WHERE 子句追加 AND 条件（row_filter_expression）

**Alternatives considered**:
- Prompt 注入权限约束: 不可靠，LLM 可能忽略
- 数据库视图: 每个用户创建视图不现实，管理成本高
- 应用层结果过滤: 性能差（先查全量再过滤），且可能泄露行数信息

## 多角色权限合并策略

**Decision**: 并集策略（最宽松原则）

**Rationale**: 用户同时拥有多个角色时，取所有角色权限的并集。这是企业系统的常见做法，避免"加了角色反而权限变少"的反直觉行为。

**Merge rules**:
- allowedTables: 所有角色允许的表取并集
- deniedColumns: 所有角色禁止的列取交集（只有所有角色都禁止才真正禁止）
- maskColumns: 如果任一角色允许明文访问，则不脱敏
- rowFilters: 多个过滤条件用 OR 合并（看到更多数据）

## 脱敏策略

**Decision**: Java 网关层执行脱敏，在返回前端之前处理

**Rationale**: 脱敏在 Java 层执行而非 SQL 层，因为：(1) SQL 层脱敏会影响聚合计算准确性；(2) Java 层可以根据用户角色动态决定是否脱敏；(3) 脱敏规则变更不需要重新生成 SQL。

**Masking rules**:
- PHONE: `138****5678` (保留前3后4)
- ID_CARD: `3101**********1234` (保留前4后4)
- EMAIL: `zha***@example.com` (保留前3 + 域名)
- BANK_CARD: `****5678` (仅保留后4)
- NAME: `张*` (保留姓)

## 权限数据传递方式

**Decision**: Java 每次请求将权限上下文作为参数传给 Python

**Rationale**: Python 无状态，不缓存权限数据。Java 在每次查询请求时，根据当前用户的角色和数据源，计算出具体的权限约束，以 JSON 形式传给 Python。这保证权限变更实时生效。

**Payload structure**:
```json
{
  "allowedTables": ["orders", "customers", "products"],
  "deniedColumns": {"customers": ["id_card", "bank_account"]},
  "maskColumns": {"customers": {"phone": "PHONE", "email": "EMAIL"}},
  "rowFilters": {"orders": "region = '华东'"}
}
```

## 功能权限方案

**Decision**: 基于 permission_code 的细粒度权限点 + 角色绑定

**Rationale**: 复用 001-user-module 已有的 sys_permission + sys_role_permission 表，新增数据权限相关的 permission_code。前端根据用户 permissions 列表控制菜单和按钮显示。

## Prompt 注入防护

**Decision**: 多层防护（输入预处理 + Role 隔离 + AST 兜底）

**Rationale**: 单一防护层不够可靠，采用纵深防御：
1. 输入预处理: 过滤/转义特殊指令词（如 "ignore previous instructions"）
2. Role 隔离: system prompt 中明确角色边界，user input 放在独立 message 中
3. AST 兜底: 无论 LLM 生成什么 SQL，AST 校验层都会强制执行权限约束和安全检查
