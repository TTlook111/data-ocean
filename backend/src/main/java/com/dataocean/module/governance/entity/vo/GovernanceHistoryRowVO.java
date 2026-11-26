package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 内容修订或人工审核的历史序列。
 */
@Data
@AllArgsConstructor
public class GovernanceHistoryRowVO {

    /**
     * 发生记录类型。
     */
    private String kind;

    /**
     * 原历史记录编号。
     */
    private String id;

    /**
     * 实际记录时间。
     */
    private Instant occurredAt;

    /**
     * 操作者编号。
     */
    private String actor;

    /**
     * 涉及的内容修订。
     */
    private String revisionId;

    /**
     * 修订序号。
     */
    private Integer revision;

    /**
     * 前一内容修订。
     */
    private String previousRevisionId;

    /**
     * 若为审核则列出实际动作。
     */
    private String action;

    /**
     * 当时的固定结构。
     */
    private GovernanceBasisVO structureBasis;

    /**
     * 按指定修订读取的获准内容。
     */
    private GovernanceRevisionVO detail;

    /**
     * 本次实际变动的业务字段。
     */
    private List<String> changedPaths;
}
