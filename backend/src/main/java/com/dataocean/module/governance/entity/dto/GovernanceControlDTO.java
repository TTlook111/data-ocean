package com.dataocean.module.governance.entity.dto;

import com.dataocean.module.iam.entity.dto.ActionReasonDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 核对并提交对当前稳定对象的人工禁止。
 *
 * @param action 沿用影响核对动作 FORBID
 * @param expectedVersion 核对时的当前聚合版本
 * @param proposal 共用的非秘密理由提案
 * @param reviewToken 预览返回的受控凭据，正式提交必填
 */
public record GovernanceControlDTO(
        @NotBlank String action, @Positive Integer expectedVersion,
        @Valid @NotNull ActionReasonDTO proposal, String reviewToken) {}
