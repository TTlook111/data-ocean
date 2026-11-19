package com.dataocean.module.governance.entity.vo;

/**
 * 单张表说明的实际生成结果。
 *
 * @param target 真实表说明目标
 * @param state 当前生成状态
 * @param originalStructure 实际固定结构
 * @param suggestion 已成功落库且获准查看的建议
 * @param failureCode 可披露的失败原因
 */
public record GenerationTargetVO(
        GovernanceTargetVO target,
        String state,
        GovernanceBasisVO originalStructure,
        GenerationSuggestionVO suggestion,
        String failureCode) {}
