package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import tools.jackson.databind.JsonNode;

import lombok.Data;

/**
 * 数据源和任务的核对信封，按动作白名单转换为声明的具体提案。
 */
@Data
public class SourceActionPreviewDTO {

    /**
     * 本次明确核对的动作代号。
     */
    @NotBlank
    private String action;

    /**
     * 核对的资源版本。
     */
    @Min(1)
    @NotNull
    private Integer expectedVersion;

    /**
     * 按动作和结构约束校验的非秘密核对内容。
     */
    @NotNull
    private JsonNode proposal;
}
