package com.dataocean.module.knowledge.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 用户明确采纳的生成章节，在原任务中按顺序编号。
 */
public record KnowledgeDraftAdoptionDTO(
        /**
         * 选中的建议序号，从零开始。已有文档可逐节采纳。
         */
        @NotEmpty @Size(max = 12) List<@NotNull @PositiveOrZero Integer> changes) {}
