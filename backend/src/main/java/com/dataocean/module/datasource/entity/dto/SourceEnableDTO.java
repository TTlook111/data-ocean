package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.ToString;

/**
 * 启用已有草稿，连接配置以平台当前修订为准。
 */
@Data
public class SourceEnableDTO {

    /**
     * 草稿核对版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 当前有效连接测试。
     */
    @NotBlank
    private String checkId;

    /**
     * 启用核对签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
