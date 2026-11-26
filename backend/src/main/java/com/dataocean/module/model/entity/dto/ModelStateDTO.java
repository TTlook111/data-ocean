package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * 更新模型可用状态。
 */
@Data
public class ModelStateDTO {

    /**
     * 目标启停状态。
     */
    @NotNull
    private Boolean enabled;

    /**
     * 模型并发版本。
     */
    @NotNull
    @Positive
    private Integer version;
}
