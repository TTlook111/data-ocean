package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * 预览和提交共用同一明确范围。
 */
@Data
public class GenerateScopeDTO {

    /**
     * 首段实际支持的单张表说明。
     */
    @NotNull
    @Size(min = 1, max = 1)
    private List<@Valid GenerateTargetDTO> targets;

    /**
     * 正式提交使用服务端核对凭据。
     */
    @ToString.Exclude
    private String reviewToken;
}
