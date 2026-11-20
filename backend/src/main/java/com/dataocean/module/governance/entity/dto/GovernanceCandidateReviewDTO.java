package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工对固定候选提交草稿或审核结论。
 */
@Data
public class GovernanceCandidateReviewDTO {

    /**
     * 当前页面读到的候选版本。
     */
    @NotNull
    @Positive
    private Integer version;

    /**
     * 区分保存草稿和明确审核确认。
     */
    @NotNull
    @Pattern(regexp = "SAVE|CONFIRM")
    private String action;

    /**
     * 人工确认的业务名称。
     */
    @Size(max = 80)
    private String name;

    /**
     * 关联条件或指标含义。
     */
    @Size(max = 4000)
    private String meaning;

    /**
     * 数量关系、完整键或指标公式及筛选条件。
     */
    @Size(max = 4000)
    private String rule;

    /**
     * 本规则适用边界与风险说明。
     */
    @Size(max = 4000)
    private String usage;
}
