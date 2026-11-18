package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;

/**
 * 人工核实的事实结论及必要依据。
 */
@Data
public class ChangeReviewProposalDTO {

    /**
     * 当前变化类型允许的结论代码。
     */
    @NotBlank
    private String decision;

    /**
     * 核实实际看到的来源和原因。
     */
    @NotBlank
    @CodePointLength(max = 1000)
    private String evidence;
}
