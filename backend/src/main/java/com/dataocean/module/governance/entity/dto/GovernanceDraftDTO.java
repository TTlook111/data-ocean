package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 带并发条件保存当前条目的治理草稿。
 */
@Data
public class GovernanceDraftDTO {

    /**
     * 当次采用的条目和结构版本。
     */
    @Valid
    @NotNull
    private EntryPreconditionDTO precondition;

    /**
     * 拟保存的业务定义及依据。
     */
    @Valid
    @NotNull
    private GovernanceDraftInputDTO draft;
}
