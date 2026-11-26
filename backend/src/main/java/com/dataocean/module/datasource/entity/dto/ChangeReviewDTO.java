package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.ToString;

/**
 * 变化核实的实际提交，复用与预览一致的非秘密提案。
 */
@Data
public class ChangeReviewDTO {

    /**
     * 核实时的变化版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 实际结论及证据。
     */
    @NotNull
    @Valid
    private ChangeReviewProposalDTO proposal;

    /**
     * 受控核对签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
