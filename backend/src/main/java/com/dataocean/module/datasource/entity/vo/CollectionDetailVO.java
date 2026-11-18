package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 任务详情仅呈现实际状态允许披露的内容。
 *
 * @param taskId 任务编号
 * @param taskNo 展示编号
 * @param sourceId 归属源
 * @param triggerKind 发起方式
 * @param actor 发起主体
 * @param scopeLabel 用户可见的本次采集范围
 * @param scope 固定使用的数据库与 Schema
 * @param account 固定的只读账号
 * @param createdAt 接收时间
 * @param startedAt 真实开始时间
 * @param endedAt 真实结束时间
 * @param parentTaskId 人工重试对应原任务
 * @param configRevision 固定的连接修订
 * @param state 当前五态
 * @param executionState 源侧执行事实
 * @param version 当前状态版本
 * @param streamEpoch 执行事件的代次标识
 * @param stopProgress 取消及停止进度
 * @param content 当前状态允许显示的内容
 */
public record CollectionDetailVO(
        String taskId,
        String taskNo,
        String sourceId,
        String triggerKind,
        Actor actor,
        String scopeLabel,
        Scope scope,
        String account,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        String parentTaskId,
        int configRevision,
        String state,
        String executionState,
        int version,
        String streamEpoch,
        StopProgress stopProgress,
        Content content) {

    /**
     * 任务事件中的安全主体引用。
     *
     * @param kind USER 或 SYSTEM
     * @param id 用户编号，系统为空
     * @param loginName 登录名，系统为空
     * @param name 展示姓名或固定系统名称
     */
    public record Actor(String kind, String id, String loginName, String name) {}

    /**
     * 共用任务和目录的只读范围。
     *
     * @param databaseType 数据库类型
     * @param databaseName 目标库名
     * @param schemas 此修订的 PostgreSQL 范围
     */
    public record Scope(String databaseType, String databaseName, List<String> schemas) {}

    /**
     * 当前可显示的停止观察。
     *
     * @param cancelRequestedAt 接收取消意图的时间
     * @param stopConfirmedAt 确认所有源执行结束的时间
     * @param executionState 独立状态
     * @param message 可披露原因
     */
    public record StopProgress(
            Instant cancelRequestedAt, Instant stopConfirmedAt, String executionState, String message) {}

    /**
     * 状态内容由后端裁定，失败不带候选计数。
     *
     * @param phase 当前读取阶段
     * @param counts 运行中进度
     * @param snapshotId 成功快照
     * @param previousSuccessSnapshotId 上一次成功快照
     * @param comparisonSnapshotId 实际比较快照
     * @param collectedObjectCount 成功读取对象数
     * @param analysis 成功分析结果
     * @param changeCount 发现的变化数量
     * @param failureCode 稳定失败原因代码
     * @param failureMessage 可披露失败原因
     * @param reason 取消原因
     */
    public record Content(
            String phase,
            Counts counts,
            String snapshotId,
            String previousSuccessSnapshotId,
            String comparisonSnapshotId,
            Long collectedObjectCount,
            String analysis,
            Long changeCount,
            String failureCode,
            String failureMessage,
            String reason) {}

    /**
     * 客户端可采用的明确采集计数。
     *
     * @param enumerationComplete 对象枚举是否完整
     * @param discoveredCount 已发现数
     * @param totalCount 完整枚举前未知
     * @param attemptedCount 已尝试读取数
     * @param completeCount 完整结构数
     * @param partialCount 部分结构数
     * @param failedCount 读取失败数
     * @param unprocessedCount 当前尚未处理数
     */
    public record Counts(
            boolean enumerationComplete,
            long discoveredCount,
            Long totalCount,
            long attemptedCount,
            long completeCount,
            long partialCount,
            long failedCount,
            long unprocessedCount) {}
}
