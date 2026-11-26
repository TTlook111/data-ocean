package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标的可执行口径，模型仅提出草稿，确认后随固定指标修订发布。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricRulesDTO {

    /**
     * NONE 表示无需默认业务日，BUSINESS_DAY 表示必须按指定业务日期列查询。
     */
    @Pattern(regexp = "NONE|BUSINESS_DAY")
    private String dateMode;

    /**
     * 业务日期的稳定 DATE 列编号，仅在 BUSINESS_DAY 时填写。
     */
    @Size(max = 20)
    private String dateFieldId;

    /**
     * 指标必需的等值条件，空数组表示已确认没有固定条件。
     */
    @Size(max = 8)
    private List<@Valid MetricFilterDTO> fixedFilters;

    /**
     * NONE 表示无计量单位，FIXED 使用统一单位，COLUMN 要求所有参与行的列值一致。
     */
    @Pattern(regexp = "NONE|FIXED|COLUMN")
    private String unitMode;

    /**
     * FIXED 模式下已确认的单位或币种。
     */
    @Size(max = 32)
    private String unit;

    /**
     * COLUMN 模式下保存计量单位或币种的稳定列编号。
     */
    @Size(max = 20)
    private String unitFieldId;
}
