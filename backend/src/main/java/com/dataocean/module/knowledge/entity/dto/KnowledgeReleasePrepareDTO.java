package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * 创建任务时记录当前页面核对的单文档修订。
 */
@Data
public class KnowledgeReleasePrepareDTO {

    /**
     * 当前源内的知识文档。
     */
    @NotBlank
    private String documentId;

    /**
     * 界面核对过的确切文档确认。
     */
    @NotBlank
    private String documentRevisionId;
}
