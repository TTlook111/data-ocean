package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 已存在的标准题更新固定实际读到的版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeTestUpdateDTO extends KnowledgeTestDraftDTO {

    /**
     * 客户端实际读取的聚合版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;
}
