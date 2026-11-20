package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 展示人工确认的实际记录而非推断状态。
 */
@Data
@AllArgsConstructor
public class GovernanceConfirmationVO {

    /**
     * 人工审核记录。
     */
    private String id;

    /**
     * 确认定义或确认移除。
     */
    private String action;

    /**
     * 实际确认的修订。
     */
    private String revisionId;

    /**
     * 实际审核人。
     */
    private String confirmedBy;

    /**
     * 人工审核时刻。
     */
    private Instant confirmedAt;

    /**
     * 当次核对的结构。
     */
    private GovernanceBasisVO structureBasis;
}
