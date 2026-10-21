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
    private String snapshotStatus;
    private String requestedScope;
    private Long checkBatchId;
    private String status;
    private Boolean complete;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private Integer executedRuleCount;
    private Integer failedRuleCount;
    private Integer notApplicableRuleCount;
    private String errorSummary;
    private java.util.List<QualityCheckBatchRuleVO> rules;
    private BigDecimal qualityScore;
    private Map<String, BigDecimal> dimensionScores;
    private Integer totalIssues;
    private Map<String, Integer> issueCount;
}
