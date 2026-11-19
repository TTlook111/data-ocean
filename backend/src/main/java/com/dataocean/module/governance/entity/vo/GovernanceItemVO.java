package com.dataocean.module.governance.entity.vo;

import java.util.List;

/**
 * 治理条目详情及当前可办理动作。
 *
 * @param target 当前稳定条目
 * @param itemId 持久化条目编号，空条目可为空
 * @param version 当前并发版本，未落库为空
 * @param structureBasis 当前适用或最后成功的来源结构
 * @param state 空白、草稿或有效确认
 * @param pendingReview 存在来源影响待处理
 * @param currentRevision 可披露的当前内容
 * @param latestConfirmation 最后一次实际人工确认
 * @param gaps 可披露的具体核对事项
 * @param impacts 来源变化及进度
 * @param blocked 当前生产使用是否被禁止
 * @param availability 当前结构治理可用性
 * @param displayName 当前原字段或表说明的展示名称
 * @param nativeType 字段的原生类型
 * @param sourceComment 当前固定结构的原注释
 * @param actions 当前各动作按钮是否具备权限
 */
public record GovernanceItemVO(
        GovernanceTargetVO target,
        String itemId,
        Integer version,
        GovernanceBasisVO structureBasis,
        String state,
        boolean pendingReview,
        GovernanceRevisionVO currentRevision,
        GovernanceConfirmationVO latestConfirmation,
        List<GovernanceGapVO> gaps,
        List<GovernanceImpactVO> impacts,
        boolean blocked,
        String availability,
        String displayName,
        String nativeType,
        String sourceComment,
        GovernanceActionsVO actions) {}
