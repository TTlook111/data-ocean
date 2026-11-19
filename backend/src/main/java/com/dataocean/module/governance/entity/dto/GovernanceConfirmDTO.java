package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 直接确认当前定义，可在同一事务保存修改。
 *
 * @param precondition 操作者实际核对的版本
 * @param action 确认定义 CONFIRM 或确认移除 ACK_REMOVAL
 * @param draft 有编辑权且需同时保存时提交
 */
public record GovernanceConfirmDTO(
        @Valid @NotNull EntryPreconditionDTO precondition,
        @NotBlank String action,
        @Valid GovernanceDraftInputDTO draft) {}
