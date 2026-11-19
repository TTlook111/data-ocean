package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 资料变化待定位入口及实际办理结果。
 *
 * @param id 资料变化稳定事项编号
 * @param sourceId 当前数据源
 * @param version 当前待定位并发版本
 * @param occurredAt 原资料变动时刻
 * @param mappingState 是否仍有待定位
 * @param stage 实际处理汇总
 * @param origin 获准的资料及历史修订
 * @param targets 关联条目及复核结果
 */
public record GovernanceNoteChangeVO(
        String id,
        String sourceId,
        int version,
        Instant occurredAt,
        String mappingState,
        String stage,
        GovernanceOriginVO origin,
        List<GovernanceImpactVO> targets) {}
