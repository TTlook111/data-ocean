package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 生成任务安全回读和诊断状态。
 */
@Data
@AllArgsConstructor
public class GenerationDetailVO {

    /**
     * 原任务。
     */
    private String requestId;

    /**
     * 原命令。
     */
    private String commandId;

    /**
     * 归属来源。
     */
    private String sourceId;

    /**
     * 对象身份。
     */
    private String objectId;

    /**
     * 实际发起人。
     */
    private String requestedBy;

    /**
     * 实际状态。
     */
    private String state;

    /**
     * 当前快照版本。
     */
    private int version;

    /**
     * 执行时刻。
     */
    private Instant startedAt;

    /**
     * 任务终态时刻。
     */
    private Instant endedAt;

    /**
     * 用户可见的稳定原因。
     */
    private String failureCode;

    /**
     * 实际目标。
     */
    private List<GenerationTargetVO> targets;

    /**
     * 可披露的当次资料取用记录。
     */
    private List<GenerationNoteVO> notes;
}
