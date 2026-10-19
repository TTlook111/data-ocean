package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class QualityRuleRevisionVO {
    private Long id;
    private Long ruleId;
    private String ruleCode;
    private String templateCode;
    private Integer configVersion;
    private String action;
    private String ruleName;
    private String dimension;
    private String severity;
    private String description;
    private String scopeType;
    private Long datasourceId;
    private String targetTableName;
    private String targetColumnName;
    private Map<String, Object> parameters;
    private Integer enabled;
    private BigDecimal deductionPoints;
    private Boolean deleted;
    private Long changedBy;
    private LocalDateTime changedAt;
}
