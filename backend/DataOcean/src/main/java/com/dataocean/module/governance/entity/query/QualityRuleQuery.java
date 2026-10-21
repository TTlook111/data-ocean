package com.dataocean.module.governance.entity.query;

import lombok.Data;

/** 质量规则列表筛选。 */
@Data
public class QualityRuleQuery {
    private Long datasourceId;
    private String scopeType;
    private String dimension;
    private String templateCode;
    private String ruleName;
    private Boolean enabled;
    private Boolean deleted;
}
