package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 把实际答复与所修正的治理内容一起保存。
 */
@Data
public class GovernanceGapResolutionDTO {

    /**
     * 当前条目的开放核对事项编号。
     */
    @NotBlank
    private String gapId;

    /**
     * 实际的人工核对结论。
     */
    @NotBlank
    @Size(max = 1000)
    private String resolution;
}
