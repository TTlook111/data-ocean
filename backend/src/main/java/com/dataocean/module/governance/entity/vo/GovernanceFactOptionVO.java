package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 知识选择器显示稳定身份和当前不可纳入原因。
 */
@Data
@AllArgsConstructor
public class GovernanceFactOptionVO {

    /**
     * 来源范围内可定位的稳定身份。
     */
    private GovernanceTargetVO target;

    /**
     * 当前允许选择的确认内容，暂不可用时为空。
     */
    private GovernanceFactVO fact;

    /**
     * 不能纳入的当前原因，正常时为空。
     */
    private String unavailableReason;
}
