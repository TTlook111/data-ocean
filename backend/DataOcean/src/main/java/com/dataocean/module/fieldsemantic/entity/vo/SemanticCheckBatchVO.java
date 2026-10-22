package com.dataocean.module.fieldsemantic.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SemanticCheckBatchVO {
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private String status;
    private Integer applicableCount;
    private Integer validCount;
    private Integer missingCount;
    private Integer staleCount;
    private Integer notApplicableCount;
    private String errorSummary;
    private Integer ruleVersion;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
