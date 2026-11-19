package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

/**
 * 展示人工确认的实际记录而非推断状态。
 *
 * @param id 人工审核记录
 * @param action 确认定义或确认移除
 * @param revisionId 实际确认的修订
 * @param confirmedBy 实际审核人
 * @param confirmedAt 人工审核时刻
 * @param structureBasis 当次核对的结构
 */
public record GovernanceConfirmationVO(
        String id,
        String action,
        String revisionId,
        String confirmedBy,
        Instant confirmedAt,
        GovernanceBasisVO structureBasis) {}
