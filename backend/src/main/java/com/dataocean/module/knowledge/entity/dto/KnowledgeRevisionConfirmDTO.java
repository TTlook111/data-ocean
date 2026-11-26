package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * 文档确认和题目审核共用的并发前置条件。
 */
@Data
public class KnowledgeRevisionConfirmDTO {

    /**
     * 可被确认的当前文档版本。
     */
    @Positive
    @NotNull
    private Integer expectedVersion;

    /**
     * 实际人工阅读的修订编号。
     */
    @NotBlank
    private String revisionId;
}
