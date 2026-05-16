# Research: 字段 Tag 与可信度模块

## 可信度分数存储方案

**Decision**: 在 field_confidence 表中存储当前分数（score 字段），所有变更记录在 field_confidence_event 流水表

**Rationale**: 查询时直接读 score 字段（O(1)），无需每次从事件流重新计算。事件表用于审计追溯和问题排查。

**Alternatives considered**:
- 纯事件溯源（每次查询 SUM 所有 delta）: 查询性能差，表数据量大时不可接受
- 只存当前值不记录事件: 无法审计，无法回溯问题

## 可信度边界处理

**Decision**: score 硬限制在 [0, 100]，计算时使用 `Math.max(0, Math.min(100, currentScore + delta))`

**Rationale**: 简单直观，避免溢出。0 分字段仍可被使用但 Prompt 中标记为"极低可信"。

## 反馈限频方案

**Decision**: Redis key `feedback:neg:{userId}:{columnMetaId}` + TTL 86400s

**Rationale**: 利用 Redis TTL 自动过期，无需定时任务清理。key 粒度到用户+字段，精确控制。

**Alternatives considered**:
- 数据库 UNIQUE 约束 + 日期字段: 需要定时清理或复杂查询
- 滑动窗口限流: 过于复杂，每天 1 次的需求用简单 TTL 即可

## 群体阈值检测方案

**Decision**: 每次负向反馈提交时，COUNT 该字段近 7 天内不同用户的未审核负向反馈数，达到 3 则触发自动降级

**Rationale**: 实时检测，无需定时任务。7 天窗口避免历史反馈累积误触发。

**Alternatives considered**:
- 定时任务扫描: 延迟高，不够实时
- Redis 计数器: 需要额外维护，且重启后丢失

## 标签体系设计

**Decision**: 预定义标签存储在 tag_definition 配置中（code + name），field_tag 表引用 tag_code。支持管理员自定义扩展。

**Rationale**: 预定义标签保证一致性（如"废弃"标签有特殊语义——阻止进入 RAG），自定义标签满足灵活性。

## 并发安全

**Decision**: 可信度更新使用乐观锁（version 字段）+ 数据库行锁（SELECT FOR UPDATE）

**Rationale**: 同一字段可能被多个反馈事件并发更新。乐观锁处理低并发场景，高并发时降级为悲观锁。MVP 阶段并发量低，乐观锁足够。
