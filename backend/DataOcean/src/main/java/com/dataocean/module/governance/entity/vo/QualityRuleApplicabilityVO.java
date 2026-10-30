package com.dataocean.module.governance.entity.vo;

import lombok.Data;

/** 当前配置对选定快照的只读规划结果，不是执行结果或业务适用性结论。 */
@Data
public class QualityRuleApplicabilityVO {
    private Long ruleId;
    private String ruleName;
    private Integer configVersion;
    private String scopeType;
    private String templateCode;
    private String status;
    private String reason;
    private Integer targetCount;
    private Integer plannedTargetCount;
}
