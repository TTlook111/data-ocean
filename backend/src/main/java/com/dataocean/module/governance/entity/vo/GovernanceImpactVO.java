package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 有实际依赖的来源复核及其进度。
 */
@Data
@AllArgsConstructor
public class GovernanceImpactVO {

    /**
     * 复核事项编号。
     */
    private String impactId;

    /**
     * 新增、复核或移除。
     */
    private String kind;

    /**
     * 当前办理状态。
     */
    private String state;

    /**
     * 受影响的稳定条目。
     */
    private GovernanceTargetVO target;

    /**
     * 实际变化涉及的表说明或字段名。
     */
    private String targetName;

    /**
     * 实际开始办理时间。
     */
    private Instant startedAt;

    /**
     * 业务审核完成时间。
     */
    private Instant resolvedAt;

    /**
     * 审核本次来源的确认。
     */
    private String confirmationId;

    /**
     * 下游是否需要处理。
     */
    private Boolean knowledgeRequired;

    /**
     * 获准的来源定位。
     */
    private GovernanceOriginVO origin;
}
