package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 更新模型可用状态。
 *
 * @param enabled 目标启停状态
 * @param version 模型并发版本
 */
public record ModelStateDTO(@NotNull Boolean enabled, @NotNull @Positive Integer version) {
}
