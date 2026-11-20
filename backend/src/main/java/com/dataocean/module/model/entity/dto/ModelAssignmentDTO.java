package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * 更新一个业务节点的模型选择。
 */
@Data
public class ModelAssignmentDTO {

    /**
     * 所选模型，清空时为 null。
     */
    @Positive
    private Long modelId;

    /**
     * 节点并发版本。
     */
    @NotNull
    @Positive
    private Integer version;
}
