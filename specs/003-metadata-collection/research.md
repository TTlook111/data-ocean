# Technical Research: 元数据采集模块

**Date**: 2026-05-16

## Decision 1: 采集方式选择

**Options**:
1. JDBC DatabaseMetaData API — 标准 Java API，跨数据库
2. 直接查询 information_schema — MySQL 专用，信息更丰富
3. SHOW 命令解析 — 非结构化，解析复杂

**Decision**: 方案 1 + 2 混合

**Rationale**:
- DatabaseMetaData 提供标准化的表、字段、索引、外键信息，代码可读性好
- information_schema 补充 DatabaseMetaData 缺失的信息：表注释（TABLE_COMMENT）、字段注释（COLUMN_COMMENT）、行数估算（TABLE_ROWS）、字符集等
- 两者结合覆盖所有需要的元数据维度

**Implementation**:
```
1. DatabaseMetaData.getTables() → 表列表
2. DatabaseMetaData.getColumns() → 字段基础信息
3. information_schema.COLUMNS → 字段注释、字符集
4. information_schema.TABLES → 表注释、行数估算、数据大小
5. DatabaseMetaData.getPrimaryKeys() → 主键
6. DatabaseMetaData.getImportedKeys() → 外键
7. DatabaseMetaData.getIndexInfo() → 索引
```

## Decision 2: 统计信息采集策略

**Options**:
1. 全量精确统计 — 对每个字段执行 COUNT/DISTINCT/NULL 查询
2. 采样统计 — TABLESAMPLE 或 LIMIT 采样
3. information_schema 估算 — 仅用系统表的估算值
4. 混合策略 — 小表精确，大表采样

**Decision**: 方案 4 — 混合策略

**Rationale**:
- 行数 < 10000 的表：精确统计（空值率、枚举 TopN）
- 行数 >= 10000 的表：采样 10000 行统计
- 行数估算统一使用 information_schema.TABLES.TABLE_ROWS（免查询）
- 统计采集作为独立步骤，可跳过（首次同步可不采集统计，后续补充）

**Performance**:
- 100 张表（平均 20 字段）全量统计预计 30-60s
- 1000 张表采样统计预计 5-10min

## Decision 3: 快照存储结构

**Options**:
1. 扁平存储 — 所有表/字段信息直接存管理库表
2. JSON 快照 — 整个 Schema 序列化为 JSON 存储
3. 扁平 + JSON 摘要

**Decision**: 方案 1 — 扁平存储

**Rationale**:
- 后续治理模块需要对单个字段进行状态标记、质量评分，扁平存储支持细粒度操作
- 差异对比需要字段级别的比较，扁平存储查询效率高
- JSON 快照不利于索引和查询
- 快照通过 snapshot_id 关联所有表/字段记录，逻辑上仍是一个整体

## Decision 4: 同步任务并发控制

**Options**:
1. 无并发控制 — 允许同一数据源多次同步
2. 数据库锁 — SELECT FOR UPDATE
3. Redis 分布式锁
4. 应用层状态检查

**Decision**: 方案 4 — 应用层状态检查

**Rationale**:
- MVP 单实例部署，不需要分布式锁
- 同步前检查该数据源是否有 RUNNING 状态的任务，有则拒绝
- 任务状态：PENDING → RUNNING → SUCCESS/FAILED
- 超时保护：RUNNING 超过 30 分钟自动标记为 TIMEOUT

## Decision 5: 表过滤规则

**Rule**:
- 默认排除系统表（mysql.*, information_schema.*, performance_schema.*）
- 支持配置排除前缀（如 `tmp_`, `bak_`, `test_`）
- 排除规则存储在 datasource 配置中，JSON 数组格式
- 未来支持正则表达式过滤

## Decision 6: 历史 SQL 解析（MVP 范围内）

**Scope**: 从 MySQL 慢查询日志或 general_log 中提取历史 SQL，分析表关联模式。

**MVP 简化**: 暂不实现自动日志解析。提供手动导入接口，管理员可上传 SQL 文件，系统解析出表关联关系作为 table_relation 的补充来源。

**后续迭代**: 接入 MySQL binlog 或 ProxySQL 审计日志实现自动化。
