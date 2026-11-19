package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审核时记录具体的待核清问题。
 *
 * @param precondition 当前操作所基于的版本
 * @param contentPath 有实际缺口的业务内容字段
 * @param question 需要人工确认的具体问题
 */
public record GovernanceGapDTO(
        @Valid @NotNull EntryPreconditionDTO precondition,
        @NotBlank String contentPath,
        @NotBlank @Size(max = 1000) String question) {}
