package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 采纳原任务中实际展示的一道建议。
 */
public record KnowledgeTestSuggestionAdoptionDTO(
        /**
         * 结果中建议的零起始顺序。
         */
        @NotNull @PositiveOrZero Integer index) {}
