package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 展示治理详情需要的对象上下文和办理状态。
 */
@Data
@AllArgsConstructor
public class GovernanceObjectVO {

    /**
     * 治理任务当前来源。
     */
    private String sourceId;

    /**
     * 当前来源显示名。
     */
    private String sourceName;

    /**
     * 稳定对象。
     */
    private String objectId;

    /**
     * 对象世代。
     */
    private int generation;

    /**
     * 限定路径。
     */
    private String qualifiedName;

    /**
     * 对象类型。
     */
    private String objectType;

    /**
     * 来源原注释。
     */
    private String sourceComment;

    /**
     * 详情的当前结构依据。
     */
    private GovernanceBasisVO structureBasis;

    /**
     * 当前对象是否可办理。
     */
    private String availability;

    /**
     * 同表全部待核实事实数。
     */
    private int pendingFactCount;

    /**
     * 实际表说明条目。
     */
    private GovernanceItemVO tableEntry;

    /**
     * 优先可定位的待治理条目。
     */
    private GovernanceTargetVO nextPendingTarget;

    /**
     * 同表各条目的实际来源影响。
     */
    private List<GovernanceImpactVO> pendingImpacts;
}
