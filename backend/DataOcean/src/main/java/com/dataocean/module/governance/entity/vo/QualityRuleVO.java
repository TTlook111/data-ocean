package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class QualityRuleVO {
    private Long id;
    private String ruleCode;
    private String templateCode;
    private String ruleName;
    private String dimension;
    private String severity;
    private String description;
    private String checkTarget;
    private String checkType;
    private String scopeType;
    private Long datasourceId;
    private String targetTableName;
    private String targetColumnName;
    private Map<String, Object> parameters;
    private Integer configVersion;
    private Integer enabled;
    private BigDecimal deductionPoints;
    private Integer builtin;
    private Boolean supported;
    private String unsupportedReason;
    private Boolean overridden;
    private String overriddenBy;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
