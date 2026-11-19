package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 带并发条件保存当前条目的治理草稿。
 *
 * @param precondition 当次采用的条目和结构版本
 * @param draft 拟保存的业务定义及依据
 */
public record GovernanceDraftDTO(
        @Valid @NotNull EntryPreconditionDTO precondition,
        @Valid @NotNull GovernanceDraftInputDTO draft) {}
