package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

/**
 * 实际凭据替换结果，候选秘密不进入展示或日志。
 *
 * @param changeId 替换操作编号
 * @param sourceId 来源编号
 * @param commandId 原命令编号
 * @param state 替换状态
 * @param blockingTaskId 等待停止的原任务
 * @param executionState 源执行状态
 * @param acceptedAt 接收动作时间
 * @param stopConfirmedAt 确认旧任务停止时间
 * @param completedAt 替换结束时间
 * @param currentConfigurationRevision 当前配置修订
 * @param failureCode 替换失败类型
 * @param message 可披露替换结果
 */
public record CredentialChangeVO(
        String changeId,
        String sourceId,
        String commandId,
        String state,
        String blockingTaskId,
        String executionState,
        Instant acceptedAt,
        Instant stopConfirmedAt,
        Instant completedAt,
        int currentConfigurationRevision,
        String failureCode,
        String message) {}
