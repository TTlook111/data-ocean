package com.dataocean.module.governance.entity.dto;

import com.dataocean.module.iam.entity.dto.ActionReasonDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.ToString;

/**
 * 核对并提交对当前稳定对象的人工禁止。
 */
@Data
public class GovernanceControlDTO {

    /**
     * 沿用影响核对动作 FORBID。
     */
    @NotBlank
    private String action;

    /**
     * 核对时的当前聚合版本。
     */
    @Positive
    private Integer expectedVersion;

    /**
     * 共用的非秘密理由提案。
     */
    @Valid
    @NotNull
    private ActionReasonDTO proposal;

    /**
     * 预览返回的受控凭据，正式提交必填。
     */
    @ToString.Exclude
    private String reviewToken;
}
