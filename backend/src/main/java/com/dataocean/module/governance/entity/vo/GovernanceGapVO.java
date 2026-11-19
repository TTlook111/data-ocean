package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

/**
 * 当前业务核对项和已提交的人工核清记录。
 *
 * @param gapId 业务待核对项编号
 * @param contentPath 应定位的业务字段
 * @param question 实际问题
 * @param state 开放或已核清
 * @param resolution 人工提交的解决说明
 * @param resolvedBy 人工核清人员编号
 * @param resolvedAt 人工核清时刻
 */
public record GovernanceGapVO(
        String gapId,
        String contentPath,
        String question,
        String state,
        String resolution,
        String resolvedBy,
        Instant resolvedAt) {}
