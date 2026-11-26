package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前业务核对项和已提交的人工核清记录。
 */
@Data
@AllArgsConstructor
public class GovernanceGapVO {

    /**
     * 业务待核对项编号。
     */
    private String gapId;

    /**
     * 应定位的业务字段。
     */
    private String contentPath;

    /**
     * 实际问题。
     */
    private String question;

    /**
     * 开放或已核清。
     */
    private String state;

    /**
     * 人工提交的解决说明。
     */
    private String resolution;

    /**
     * 人工核清人员编号。
     */
    private String resolvedBy;

    /**
     * 人工核清时刻。
     */
    private Instant resolvedAt;
}
