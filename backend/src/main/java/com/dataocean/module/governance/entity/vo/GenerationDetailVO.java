package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 生成任务安全回读和诊断状态。
 *
 * @param requestId 原任务
 * @param commandId 原命令
 * @param sourceId 归属来源
 * @param objectId 对象身份
 * @param requestedBy 实际发起人
 * @param state 实际状态
 * @param version 当前快照版本
 * @param startedAt 执行时刻
 * @param endedAt 任务终态时刻
 * @param failureCode 用户可见的稳定原因
 * @param targets 实际目标
 * @param notes 可披露的当次资料取用记录
 */
public record GenerationDetailVO(
        String requestId,
        String commandId,
        String sourceId,
        String objectId,
        String requestedBy,
        String state,
        int version,
        Instant startedAt,
        Instant endedAt,
        String failureCode,
        List<GenerationTargetVO> targets,
        List<GenerationNoteVO> notes) {}
