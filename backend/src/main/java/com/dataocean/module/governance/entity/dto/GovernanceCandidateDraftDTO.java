package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建和修改候选共用的业务定义与依赖。
 */
@Data
public class GovernanceCandidateDraftDTO {

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
     * 关联的数量关系与完整键，或指标的纯计算公式。
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
     * 指标的执行口径。省略时保留上一修订，关联候选不接收此项。
     */
    @Valid
    private MetricRulesDTO metricRules;

    /**
     * 关联的业务日期适用期，未提交时沿用已有修订。
     */
    @Valid
    private RelationWindowDTO relationWindow;

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
