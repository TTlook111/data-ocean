package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 变化核实预览只接受版本和非秘密提案。
 */
@Data
public class ChangeReviewPreviewDTO {

    /**
     * 预览时已看到的变化版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 与正式提交共用的核实依据。
     */
    @NotNull
    @Valid
    private ChangeReviewProposalDTO proposal;
}
