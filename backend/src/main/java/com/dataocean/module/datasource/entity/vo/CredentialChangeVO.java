package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实际凭据替换结果，候选秘密不进入展示或日志。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialChangeVO {

    /**
     * 替换操作编号。
     */
    private String changeId;

    /**
     * 来源编号。
     */
    private String sourceId;

    /**
     * 原命令编号。
     */
    private String commandId;

    /**
     * 替换状态。
     */
    private String state;

    /**
     * 等待停止的原任务。
     */
    private String blockingTaskId;

    /**
     * 源执行状态。
     */
    private String executionState;

    /**
     * 接收动作时间。
     */
    private Instant acceptedAt;

    /**
     * 确认旧任务停止时间。
     */
    private Instant stopConfirmedAt;

    /**
     * 替换结束时间。
     */
    private Instant completedAt;

    /**
     * 当前配置修订。
     */
    private int currentConfigurationRevision;

    /**
     * 替换失败类型。
     */
    private String failureCode;

    /**
     * 可披露替换结果。
     */
    private String message;
}
