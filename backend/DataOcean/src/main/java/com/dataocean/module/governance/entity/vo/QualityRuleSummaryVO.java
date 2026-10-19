package com.dataocean.module.governance.entity.vo;

import lombok.Data;

@Data
public class QualityRuleSummaryVO {
    private Integer enabledCount;
    private Integer applicableCount;
    private Integer unsupportedCount;
    private Integer overriddenCount;
    private Integer executedCount;
    private String executionBatchStatus;
}
