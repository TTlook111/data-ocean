package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 审核时记录具体的待核清问题。
 */
@Data
public class GovernanceGapDTO {

    /**
     * 当前操作所基于的版本。
     */
    @Valid
    @NotNull
    private EntryPreconditionDTO precondition;

    /**
     * 有实际缺口的业务内容字段。
     */
    @NotBlank
    private String contentPath;

    /**
     * 需要人工确认的具体问题。
     */
    @NotBlank
    @Size(max = 1000)
    private String question;
}
