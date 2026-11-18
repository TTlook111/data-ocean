package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.ToString;

/**
 * 数据源采集计划的暂停或恢复动作。
 */
@Data
public class ScheduleChangeDTO {

    /**
     * 暂停或恢复的动作代码。
     */
    @NotBlank
    @Pattern(regexp = "PAUSE_SCHEDULE|RESUME_SCHEDULE")
    private String action;

    /**
     * 核对时的源版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 暂停原因或恢复说明。
     */
    @NotNull
    @Valid
    private ScheduleProposalDTO proposal;

    /**
     * 此次计划变更的核对签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
