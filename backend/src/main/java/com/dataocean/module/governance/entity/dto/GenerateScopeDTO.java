package com.dataocean.module.governance.entity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * 预览和提交共用同一明确范围。
 */
@Data
public class GenerateScopeDTO {

    /**
     * 本次选中的表说明与字段，按用户选择顺序处理。
     */
    @NotNull
    @Size(max = 10)
    private List<@NotNull @Valid GenerateTargetDTO> targets;

    /**
     * 可与定义起草一并执行的关联和指标发现类型。
     */
    @NotNull
    @Size(max = 2)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<@NotNull @Pattern(regexp = "RELATION|METRIC") String> discoveryKinds = List.of();

    /**
     * 正式提交使用服务端核对凭据。
     */
    @ToString.Exclude
    private String reviewToken;
}
