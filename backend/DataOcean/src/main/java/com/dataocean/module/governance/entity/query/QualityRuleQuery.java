package com.dataocean.module.governance.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 质量规则列表筛选；分页参数沿用公共 PageQuery。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRuleQuery extends PageQuery {
    private Long datasourceId;
    private String scopeType;
    private String dimension;
    private String templateCode;
    private String ruleName;
    private Boolean enabled;
    private Boolean deleted;
}
