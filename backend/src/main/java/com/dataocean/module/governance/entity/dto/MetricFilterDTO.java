package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标确认后必须执行的源字段等值条件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricFilterDTO {

    /**
     * 已选同源依赖的稳定列编号。
     */
    @Size(max = 20)
    private String fieldId;

    /**
     * 经人工核对的源端取值，执行时按该列的原生类型绑定。
     */
    @Size(max = 120)
    private String value;
}
