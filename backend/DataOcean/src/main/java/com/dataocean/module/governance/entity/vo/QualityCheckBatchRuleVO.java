package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class QualityCheckBatchRuleVO {
    private Long batchId;
    /** 批次实际执行范围，与全局规则本身的归属范围分开。 */
    private Long batchDatasourceId;
    private Long snapshotId;
    private java.time.LocalDateTime batchStartedAt;
    private String batchStatus;
    private Long ruleId;
    private String ruleCode;
    private String templateCode;
    private Integer ruleVersion;
    private String ruleName;
    private String description;
    private String dimension;
    private String severity;
    private BigDecimal deductionPoints;
    private String scopeType;
    private Long datasourceId;
    private String targetTableName;
    private String targetColumnName;
    private Map<String, Object> parameters;
    private String status;
    private String reason;
    private Integer targetCount;
    private Integer checkedTargetCount;
    private Integer issueCount;
}
