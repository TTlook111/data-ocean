package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 直接确认当前定义，可在同一事务保存修改。
 */
@Data
public class GovernanceConfirmDTO {

    /**
     * 操作者实际核对的版本。
     */
    @Valid
    @NotNull
    private EntryPreconditionDTO precondition;

    /**
     * 确认定义 CONFIRM 或确认移除 ACK_REMOVAL。
     */
    @NotBlank
    private String action;

    /**
     * 有编辑权且需同时保存时提交。
     */
    @Valid
    private GovernanceDraftInputDTO draft;
}
