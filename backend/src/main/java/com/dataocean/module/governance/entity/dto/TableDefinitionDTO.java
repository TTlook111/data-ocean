package com.dataocean.module.governance.entity.dto;

/**
 * 明确一张表或视图的人工业务定义。
 *
 * @param businessName 表的业务名称
 * @param purpose 业务用途
 * @param rowGrain 一行记录代表的业务粒度
 * @param rowIdentity 业务记录的识别和去重规则
 * @param dataScope 业务数据范围及排除条件
 * @param timeSemantics 记录的业务时间含义
 * @param updatePolicy 业务源更新与保留说明
 * @param usageNotes 适用场景及使用限制
 * @param qualityNotes 已知质量问题
 */
public record TableDefinitionDTO(
        String businessName,
        String purpose,
        String rowGrain,
        String rowIdentity,
        String dataScope,
        String timeSemantics,
        String updatePolicy,
        String usageNotes,
        String qualityNotes) {}
