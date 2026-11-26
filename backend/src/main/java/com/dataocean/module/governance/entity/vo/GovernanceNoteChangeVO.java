package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资料变化待定位入口及实际办理结果。
 */
@Data
@AllArgsConstructor
public class GovernanceNoteChangeVO {

    /**
     * 资料变化稳定事项编号。
     */
    private String id;

    /**
     * 当前数据源。
     */
    private String sourceId;

    /**
     * 当前待定位并发版本。
     */
    private int version;

    /**
     * 原资料变动时刻。
     */
    private Instant occurredAt;

    /**
     * 是否仍有待定位。
     */
    private String mappingState;

    /**
     * 实际处理汇总。
     */
    private String stage;

    /**
     * 获准的资料及历史修订。
     */
    private GovernanceOriginVO origin;

    /**
     * 关联条目及复核结果。
     */
    private List<GovernanceImpactVO> targets;
}
