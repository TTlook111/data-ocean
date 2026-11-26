package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表列表的稳定来源、治理状态及实际待核对项。
 */
@Data
@AllArgsConstructor
public class GovernanceTableRowVO {

    /**
     * 当前来源。
     */
    private String sourceId;

    /**
     * 对象身份，待核实时可为空。
     */
    private String objectId;

    /**
     * 本次实际成功观察。
     */
    private String observationId;

    /**
     * 对象世代。
     */
    private Integer generation;

    /**
     * 原始限定路径。
     */
    private String qualifiedName;

    /**
     * 表或视图分类。
     */
    private String objectType;

    /**
     * 来源原注释。
     */
    private String sourceComment;

    /**
     * 可披露的业务名称。
     */
    private String businessName;

    /**
     * 可披露的业务用途。
     */
    private String purpose;

    /**
     * 结构办理状态。
     */
    private String availability;

    /**
     * 表说明和当前字段治理状态。
     */
    private String progress;

    /**
     * 当前有效确认数。
     */
    private int confirmedCount;

    /**
     * 必要内容总数。
     */
    private int totalCount;

    /**
     * 当前待处理内容类型。
     */
    private List<String> pendingKinds;

    /**
     * 可准确定位的当前条目复核。
     */
    private List<GovernanceImpactVO> pendingImpacts;
}
