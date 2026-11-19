package com.dataocean.module.governance.entity.vo;

import java.util.List;

/**
 * 表列表的稳定来源、治理状态及实际待核对项。
 *
 * @param sourceId 当前来源
 * @param objectId 对象身份，待核实时可为空
 * @param observationId 本次实际成功观察
 * @param generation 对象世代
 * @param qualifiedName 原始限定路径
 * @param objectType 表或视图分类
 * @param sourceComment 来源原注释
 * @param businessName 可披露的业务名称
 * @param purpose 可披露的业务用途
 * @param availability 结构办理状态
 * @param progress 表说明和当前字段治理状态
 * @param confirmedCount 当前有效确认数
 * @param totalCount 必要内容总数
 * @param pendingKinds 当前待处理内容类型
 * @param pendingImpacts 可准确定位的当前条目复核
 */
public record GovernanceTableRowVO(
        String sourceId,
        String objectId,
        String observationId,
        Integer generation,
        String qualifiedName,
        String objectType,
        String sourceComment,
        String businessName,
        String purpose,
        String availability,
        String progress,
        int confirmedCount,
        int totalCount,
        List<String> pendingKinds,
        List<GovernanceImpactVO> pendingImpacts) {}
