package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

/**
 * 有实际依赖的来源复核及其进度。
 *
 * @param impactId 复核事项编号
 * @param kind 新增、复核或移除
 * @param state 当前办理状态
 * @param target 受影响的稳定条目
 * @param targetName 实际变化涉及的表说明或字段名
 * @param startedAt 实际开始办理时间
 * @param resolvedAt 业务审核完成时间
 * @param confirmationId 审核本次来源的确认
 * @param knowledgeRequired 下游是否需要处理
 * @param origin 获准的来源定位
 */
public record GovernanceImpactVO(
        String impactId,
        String kind,
        String state,
        GovernanceTargetVO target,
        String targetName,
        Instant startedAt,
        Instant resolvedAt,
        String confirmationId,
        Boolean knowledgeRequired,
        GovernanceOriginVO origin) {}
