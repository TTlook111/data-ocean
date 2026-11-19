package com.dataocean.module.governance.entity.vo;

import java.util.List;

/**
 * 展示治理详情需要的对象上下文和办理状态。
 *
 * @param sourceId 治理任务当前来源
 * @param sourceName 当前来源显示名
 * @param objectId 稳定对象
 * @param generation 对象世代
 * @param qualifiedName 限定路径
 * @param objectType 对象类型
 * @param sourceComment 来源原注释
 * @param structureBasis 详情的当前结构依据
 * @param availability 当前对象是否可办理
 * @param pendingFactCount 同表全部待核实事实数
 * @param tableEntry 实际表说明条目
 * @param nextPendingTarget 优先可定位的待治理条目
 * @param pendingImpacts 同表各条目的实际来源影响
 */
public record GovernanceObjectVO(
        String sourceId,
        String sourceName,
        String objectId,
        int generation,
        String qualifiedName,
        String objectType,
        String sourceComment,
        GovernanceBasisVO structureBasis,
        String availability,
        int pendingFactCount,
        GovernanceItemVO tableEntry,
        GovernanceTargetVO nextPendingTarget,
        List<GovernanceImpactVO> pendingImpacts) {}
