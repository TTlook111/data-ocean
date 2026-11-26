package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 字段侧栏的必要结构和当前治理状态。
 */
@Data
@AllArgsConstructor
public class GovernanceColumnRowVO {

    /**
     * 稳定列身份。
     */
    private GovernanceTargetVO target;

    /**
     * 列源端名称。
     */
    private String name;

    /**
     * 列原生类型。
     */
    private String nativeType;

    /**
     * 是否允许空值。
     */
    private boolean nullable;

    /**
     * 列的原注释。
     */
    private String sourceComment;

    /**
     * 固定成功结构。
     */
    private GovernanceBasisVO structureBasis;

    /**
     * 当前治理状态。
     */
    private String state;

    /**
     * 业务定义需复核。
     */
    private boolean pendingReview;

    /**
     * 当前是否禁止使用。
     */
    private boolean blocked;
}
