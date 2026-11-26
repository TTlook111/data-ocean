package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 调整范围继续使用已核对的文档与确认修订，并绑定正在编辑的任务版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeReleaseScopeDTO extends KnowledgeReleasePrepareDTO {

    /**
     * 核对范围时看到的原任务并发版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;
}
