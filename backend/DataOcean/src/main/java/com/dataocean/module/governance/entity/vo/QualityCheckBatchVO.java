package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

@Data
public class QualityCheckBatchVO {
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private String requestedScope;
    private List<String> requestedDimensions;
    private List<String> requestedTableNames;
    private String status;
    private Boolean complete;
    private BigDecimal totalScore;
    private Integer ruleCount;
    private Integer executedRuleCount;
    private Integer failedRuleCount;
    private Integer notApplicableRuleCount;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Boolean legacyLimited;
    private Map<String, BigDecimal> dimensionScores;
    private List<QualityCheckBatchRuleVO> rules;
}
