package com.dataocean.module.governance.entity.vo;

import java.util.List;

/**
 * 写入或重放后取得的当前安全实体引用。
 *
 * @param commandId 实际业务命令
 * @param target 影响的表说明或字段
 * @param itemId 已建立的条目编号
 * @param version 本次写入的条目版本
 * @param currentRevision 可披露内容修订
 * @param confirmationId 本命令实际确认编号
 * @param resolvedImpactIds 本命令实际结转的影响
 * @param blocked 当前是否禁止使用
 */
public record GovernanceMutationVO(
        String commandId,
        GovernanceTargetVO target,
        String itemId,
        Integer version,
        GovernanceRevisionVO currentRevision,
        String confirmationId,
        List<String> resolvedImpactIds,
        boolean blocked) {}
