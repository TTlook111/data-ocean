# Research: 血缘与审计模块

## 审计日志写入方案

**Decision**: 使用 Spring ApplicationEvent 异步写入审计日志

**Rationale**: 查询完成后发布 QueryCompletedEvent，AuditLogListener 异步消费并写入数据库。解耦查询主流程和审计逻辑，即使审计写入失败也不影响用户体验。

**Alternatives considered**:
- 同步写入: 增加查询响应时间，不可接受
- MQ (RabbitMQ/Kafka): MVP 阶段引入 MQ 过重，Spring Event 足够
- AOP 切面: 不够灵活，难以获取查询全链路信息

## 血缘解析策略

**Decision**: Java 层不做 SQL 解析，直接存储 Python sqlglot 返回的结构化血缘数据

**Rationale**: sqlglot 是 Python 生态最强的 SQL 解析库，支持 MySQL 方言。Java 层重复实现 SQL 解析没有意义，且 Java 的 SQL 解析库（JSqlParser）对复杂 SQL 支持不如 sqlglot。

**Alternatives considered**:
- Java 层用 JSqlParser 解析: 功能不如 sqlglot，维护两套解析逻辑
- 不做血缘: 无法支持变更影响分析，不满足需求

## 数据保留与清理

**Decision**: Spring @Scheduled 定时任务，每天 02:00 执行 DELETE WHERE created_at < NOW() - INTERVAL {days} DAY，分批删除（每批 1000 条）

**Rationale**: 分批删除避免长事务锁表。凌晨执行减少对业务的影响。

**Alternatives considered**:
- MySQL Event Scheduler: 不便于应用层控制和监控
- 分区表按月分区 + DROP PARTITION: 性能最优但增加运维复杂度，MVP 阶段暂不采用
- 归档到冷存储: 增加复杂度，180 天内数据量可控

## 慢查询阈值

**Decision**: 默认 5000ms，通过 application.yml 配置 `dataocean.audit.slow-query-threshold-ms`

**Rationale**: 5 秒是业界常用的慢查询阈值。可配置支持不同环境调整。

## 模板提升方案

**Decision**: 管理员手动从审计日志中选择查询提升为模板，存入 query_template 表（复用已有模板模块）

**Rationale**: 自动提升需要复杂的相似度判断和去重逻辑，MVP 阶段人工选择更可控。管理员可以看到查询的成功率、使用频次、用户评价后做判断。

## 审计日志分表策略

**Decision**: MVP 阶段单表 + 索引优化，预留分表接口。当单表超过 500 万行时考虑按月分表。

**Rationale**: 假设日均 1000 次查询，180 天约 18 万条，单表完全可以承受。索引覆盖常用查询维度即可。

**Alternatives considered**:
- 一开始就分表: 增加开发复杂度，MVP 阶段数据量不需要
- ShardingSphere: 过重，不适合 MVP
