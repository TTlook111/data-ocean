package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
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
     * 区分保存草稿、人工确认和拒绝关系。
     */
    @NotNull
    @Pattern(regexp = "SAVE|CONFIRM|REJECT")
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

    /**
     * 指标是否为其业务口径的默认项。空值沿用所编辑版本。
     */
    private Boolean defaultMetric;

    /**
     * 人工重选的完整连接字段对。空值保留已保存技术定义。
     */
    @Size(max = 12)
    private List<@NotNull @Valid GovernanceFieldPairDTO> pairs;

    /**
     * 显式使用的计算、筛选或关联字段，空值保留原依赖。
     */
    @Size(max = 30)
    private List<@NotNull @Valid GovernanceTargetDTO> fields;
}
