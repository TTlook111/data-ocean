# Technical Research: 元数据版本与审核模块

**Date**: 2026-05-16

## Decision 1: 状态机实现方式

**Options**:
1. Spring Statemachine — 企业级状态机框架
2. 枚举 + 校验方法 — 轻量级自实现
3. 第三方库 (stateless4j, squirrel-foundation)

**Decision**: 方案 2 — 枚举 + 校验方法

**Rationale**:
- 快照状态只有 6 个，流转路径固定，不需要复杂状态机框架
- Spring Statemachine 配置繁重，学习成本高，对简单场景过度设计
- 自实现代码量小（~50行），可读性好，易于维护

**Implementation**:
```java
public enum SnapshotStatus {
    DRAFT, CHECKING, ISSUE_FOUND, APPROVED, PUBLISHED, EXPIRED;

    private static final Map<SnapshotStatus, Set<SnapshotStatus>> TRANSITIONS = Map.of(
        DRAFT, Set.of(CHECKING),
        CHECKING, Set.of(ISSUE_FOUND, APPROVED),
        ISSUE_FOUND, Set.of(APPROVED),
        APPROVED, Set.of(PUBLISHED),
        PUBLISHED, Set.of(EXPIRED, APPROVED)  // APPROVED = 紧急撤回
    );

    public boolean canTransitionTo(SnapshotStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

## Decision 2: 发布唯一性保证

**Options**:
1. 应用层检查 + 乐观锁
2. 数据库唯一约束（条件索引）
3. 事务内先更新旧版本再发布新版本

**Decision**: 方案 3 — 事务内原子操作

**Rationale**:
- MySQL 不支持条件唯一索引（PostgreSQL 的 WHERE 子句索引）
- 应用层检查存在并发问题
- 事务内操作：先将该数据源所有 PUBLISHED 快照更新为 EXPIRED，再将目标快照更新为 PUBLISHED
- 使用 SELECT FOR UPDATE 锁定该数据源的快照行，防止并发发布

**SQL**:
```sql
-- 在事务内
SELECT id FROM metadata_snapshot 
WHERE datasource_id = ? AND status = 'PUBLISHED' 
FOR UPDATE;

UPDATE metadata_snapshot SET status = 'EXPIRED', expired_at = NOW() 
WHERE datasource_id = ? AND status = 'PUBLISHED';

UPDATE metadata_snapshot SET status = 'PUBLISHED', published_at = NOW() 
WHERE id = ?;
```

## Decision 3: 发布前置条件

**Rules**:
1. 快照状态必须为 APPROVED
2. 快照关联的质量问题中不能有 status=OPEN 且 severity=HIGH 的记录
3. 快照中至少有一张表的 governance_status 为 NORMAL 或 RECOMMENDED

**Enforcement**: 在 SnapshotPublishService.publish() 方法中硬编码校验，不满足条件抛出 BusinessException。

## Decision 4: 下游通知机制

**Options**:
1. 同步调用下游服务
2. Spring ApplicationEvent（进程内异步）
3. 消息队列（RabbitMQ/Kafka）

**Decision**: 方案 2 — Spring ApplicationEvent

**Rationale**:
- MVP 单实例部署，不需要分布式消息
- ApplicationEvent 解耦发布者和消费者
- 下游模块（skills.md 生成、RAG 向量化）监听 SnapshotPublishedEvent
- 使用 @Async 异步处理，不阻塞发布操作
- 后续如需分布式，可平滑迁移到消息队列

**Event Payload**:
```java
public class SnapshotPublishedEvent extends ApplicationEvent {
    private Long snapshotId;
    private Long datasourceId;
    private Long previousSnapshotId;  // 被过期的旧快照
}
```

## Decision 5: 紧急撤回策略

**Scenario**: 快照已发布后发现严重问题，需要撤回

**Rules**:
1. PUBLISHED → APPROVED（撤回到已审核状态）
2. 撤回时自动恢复上一个 EXPIRED 快照为 PUBLISHED（如果存在）
3. 如果没有可恢复的旧快照，该数据源暂时无 PUBLISHED 快照
4. 撤回后检查是否有基于该快照生成的 skills.md，如有则标记为 NEEDS_REVIEW
5. 撤回操作记录审计日志，需填写撤回原因

**Frequency**: 预期极少发生，不做复杂优化

## Decision 6: 历史快照保留策略

**Rule**:
- EXPIRED 快照保留数据不删除，用于历史审计和对比
- 同一数据源最多保留 50 个历史快照
- 超过 50 个时，自动清理最早的 EXPIRED 快照（物理删除）
- PUBLISHED 和 APPROVED 状态的快照永不自动清理
- 清理通过定时任务执行（每周一次）

## Decision 7: 版本号策略

**Rule**:
- 每个数据源独立递增版本号（snapshot_version）
- 版本号从 1 开始，每次同步 +1
- 版本号不可重用（即使快照被删除）
- 版本号用于显示和排序，不用于业务逻辑判断
