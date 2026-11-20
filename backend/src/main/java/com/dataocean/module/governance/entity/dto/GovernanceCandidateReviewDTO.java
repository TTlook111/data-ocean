package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 修改已存在的候选时，额外绑定编辑者读到的版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GovernanceCandidateReviewDTO extends GovernanceCandidateDraftDTO {

    /**
     * 当前页面读到的候选版本。
     */
    @NotNull
    @Positive
    private Integer version;
}
