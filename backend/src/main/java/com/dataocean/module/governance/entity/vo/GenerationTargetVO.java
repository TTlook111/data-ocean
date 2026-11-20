package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单张表说明的实际生成结果。
 */
@Data
@AllArgsConstructor
public class GenerationTargetVO {

    /**
     * 真实表说明目标。
     */
    private GovernanceTargetVO target;

    /**
     * 当前生成状态。
     */
    private String state;

    /**
     * 实际固定结构。
     */
    private GovernanceBasisVO originalStructure;

    /**
     * 已成功落库且获准查看的建议。
     */
    private GenerationSuggestionVO suggestion;

    /**
     * 可披露的失败原因。
     */
    private String failureCode;
}
