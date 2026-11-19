package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把实际答复与所修正的治理内容一起保存。
 *
 * @param gapId 当前条目的开放核对事项编号
 * @param resolution 实际的人工核对结论
 */
public record GovernanceGapResolutionDTO(
        @NotBlank String gapId,
        @NotBlank @Size(max = 1000) String resolution) {}
