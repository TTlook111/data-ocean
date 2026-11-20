package com.dataocean.module.governance.entity.dto;

import lombok.Data;

/**
 * 明确一张表或视图的人工业务定义。
 */
@Data
public class TableDefinitionDTO {

    /**
     * 表的业务名称。
     */
    private String businessName;

    /**
     * 业务用途。
     */
    private String purpose;

    /**
     * 一行记录代表的业务粒度。
     */
    private String rowGrain;

    /**
     * 业务记录的识别和去重规则。
     */
    private String rowIdentity;

    /**
     * 业务数据范围及排除条件。
     */
    private String dataScope;

    /**
     * 记录的业务时间含义。
     */
    private String timeSemantics;

    /**
     * 业务源更新与保留说明。
     */
    private String updatePolicy;

    /**
     * 适用场景及使用限制。
     */
    private String usageNotes;

    /**
     * 已知质量问题。
     */
    private String qualityNotes;
}
