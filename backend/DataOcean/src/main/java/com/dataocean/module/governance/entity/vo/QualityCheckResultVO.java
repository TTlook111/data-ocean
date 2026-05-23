package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 质量校验结果视图对象。
 */
@Data
public class QualityCheckResultVO {

    private Long snapshotId;
    private BigDecimal qualityScore;
    private Map<String, BigDecimal> dimensionScores;
    private Integer totalIssues;
    private Map<String, Integer> issueCount;
}
