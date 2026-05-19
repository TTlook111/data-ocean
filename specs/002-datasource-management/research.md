# Technical Research: 数据源管理模块

**Date**: 2026-05-16

## Decision 1: 密码加密方案

**Options**:
1. Jasypt (org.jasypt) — Spring Boot 集成成熟，支持 PBE + AES
2. 自研 AES-256-GCM 工具类 — 基于 JCE，完全可控
3. HashiCorp Vault — 企业级密钥管理

**Decision**: 方案 2 — 自研 AES-256-GCM 工具类

**Rationale**:
- Jasypt 默认使用 PBE 而非纯 AES-256，配置复杂
- Vault 对毕设项目过重，部署成本高
- 自研基于 `javax.crypto.Cipher` + AES/GCM/NoPadding，代码量小（~80行），安全性满足需求
- 密钥从环境变量 `DATAOCEAN_ENCRYPT_KEY` 读取，不硬编码

**Implementation**:
```java
// AES-256-GCM, 12-byte IV, 128-bit auth tag
// 存储格式: Base64(IV + ciphertext + tag)
// 密钥: 从环境变量读取 32-byte key
```

## Decision 2: 连接池管理策略

**Options**:
1. 每次查询临时创建连接 — 简单但性能差
2. 全局 HikariCP 连接池 Map — 按 datasource_id 维护
3. 仅在 Python 侧维护连接池 — Java 不持有业务库连接

**Decision**: 方案 3 — Java 不持有业务库连接池

**Rationale**:
- 架构设计中 Java 层不直接查询业务库，SQL 执行在 Python 侧
- Java 仅在"测试连接"时临时创建单连接验证，用完即关
- Python 侧通过 SQLAlchemy 连接池管理实际查询连接
- 减少 Java 侧资源占用和连接泄漏风险

**Exception**: 元数据采集（003模块）需要 Java 通过 JDBC 读取 information_schema，该场景使用临时连接，不维护长连接池。

## Decision 3: 只读账号校验方式

**Options**:
1. 信任用户填写 — 不校验
2. 尝试执行 CREATE TEMPORARY TABLE 验证失败 — 证明无写权限
3. 查询 mysql.user 表的权限列 — 需要额外权限

**Decision**: 方案 2 — 尝试写操作验证失败

**Rationale**:
- 方案 3 需要 SELECT on mysql.user 权限，很多只读账号没有
- 方案 2 通过 `CREATE TEMPORARY TABLE _dataocean_perm_check (id INT)` 尝试，如果成功说明有写权限，回滚并拒绝；如果失败（Access denied）说明确实只读
- 作为可选校验，不阻塞保存，仅给出警告

**MVP 简化**: MVP 阶段仅做 SELECT 1 连通性测试 + 界面提示"请确保使用只读账号"，不做强制写权限校验。后续迭代加入。

## Decision 4: 数据源删除策略

**Options**:
1. 硬删除 — 直接 DELETE
2. 软删除 — deleted 标记
3. 禁止删除有依赖的数据源

**Decision**: 方案 2 + 3 结合

**Rationale**:
- 使用 MyBatis-Plus 逻辑删除（`@TableLogic`）
- 如果数据源下有未过期的元数据快照或已发布的 skills.md，禁止删除，提示先处理依赖
- 已禁用且无依赖的数据源可以软删除

## Decision 5: 重复数据源检测

**Rule**: 通过 `host + port + database_name` 组合唯一性检测，新增时如果已存在相同组合则阻止保存并返回 409，避免同一业务库被重复接入导致权限、健康检查和后续元数据采集结果分叉。
