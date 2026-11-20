package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批内单个治理定义或发现任务的实际状态和结果。
 */
@Data
@AllArgsConstructor
public class GenerationTargetVO {

    /**
     * 浏览器定位该项时使用的稳定表或字段。
     */
    private GovernanceTargetVO target;

    /**
     * 单项执行编号，用来区分共享同一表的候选发现。
     */
    private String requestId;

    /**
     * 关联或指标发现类型，定义起草为空。
     */
    private String discoveryKind;

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

    /**
     * 有当前数据源访问权时可阅读的实际采样摘要。
     */
    private List<GenerationSampleVO> samples;
}
