package com.dataocean.module.access.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 某一数据源及其选定的共享输出限制。
 */
@Data
public class SourceRuleSelectionDTO {

    /**
     * 数据源编号。
     */
    @NotBlank
    private String sourceId;

    /**
     * 至少一条当前输出规则。
     */
    @NotEmpty
    @Size(max = 30)
    private List<@NotBlank String> ruleIds;
}
