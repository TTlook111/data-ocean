package com.dataocean.module.governance.entity.vo;

/**
 * 字段侧栏的必要结构和当前治理状态。
 *
 * @param target 稳定列身份
 * @param name 列源端名称
 * @param nativeType 列原生类型
 * @param nullable 是否允许空值
 * @param sourceComment 列的原注释
 * @param structureBasis 固定成功结构
 * @param state 当前治理状态
 * @param pendingReview 业务定义需复核
 * @param blocked 当前是否禁止使用
 */
public record GovernanceColumnRowVO(
        GovernanceTargetVO target,
        String name,
        String nativeType,
        boolean nullable,
        String sourceComment,
        GovernanceBasisVO structureBasis,
        String state,
        boolean pendingReview,
        boolean blocked) {}
