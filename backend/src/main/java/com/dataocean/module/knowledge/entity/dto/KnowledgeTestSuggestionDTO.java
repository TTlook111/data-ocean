package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发起标准题建议时固定用户选定的文档确认。
 */
public record KnowledgeTestSuggestionDTO(
        /**
         * 所属来源内的知识文档编号。
         */
        @NotBlank String documentId,

        /**
         * 页面实际显示的文档确认修订。
         */
        @NotBlank String documentRevisionId) {}
