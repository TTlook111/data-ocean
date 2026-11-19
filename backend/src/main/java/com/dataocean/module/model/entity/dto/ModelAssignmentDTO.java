package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 更新一个业务节点的模型选择。
 *
 * @param modelId 所选模型，清空时为 null
 * @param version 节点并发版本
 */
public record ModelAssignmentDTO(@Positive Long modelId, @NotNull @Positive Integer version) {
}
