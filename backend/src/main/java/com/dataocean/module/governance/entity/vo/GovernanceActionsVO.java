package com.dataocean.module.governance.entity.vo;

/**
 * 给界面提供当前可以调用的操作按钮。
 *
 * @param canEdit 可编辑
 * @param canSuggest 可生成建议
 * @param canConfirm 可确认内容
 * @param canSupplement 可请求补充
 * @param canForbid 可禁止使用
 * @param canAcknowledgeRemoval 可确认移除
 */
public record GovernanceActionsVO(
        boolean canEdit,
        boolean canSuggest,
        boolean canConfirm,
        boolean canSupplement,
        boolean canForbid,
        boolean canAcknowledgeRemoval) {}
