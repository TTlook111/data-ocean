package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class QualityRuleSummaryVO {
    private Long datasourceId;
    private Long snapshotId;
    private Integer enabledCount;
    private Integer globalCount;
    private Integer datasourceCount;
    private Integer applicableCount;
    private Integer notApplicableCount;
    private Integer invalidCount;
    private Integer unsupportedCount;
    private Integer overriddenCount;
    private Integer executedCount;
    private String executionBatchStatus;
    private List<QualityRuleApplicabilityVO> rules = List.of();
}
