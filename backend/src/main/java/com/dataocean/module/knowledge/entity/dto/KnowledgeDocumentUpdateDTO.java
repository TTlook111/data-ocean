package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 保存已有草稿时固定读到的文档版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeDocumentUpdateDTO extends KnowledgeDocumentDraftDTO {

    /**
     * 当前聚合并发版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;
}
