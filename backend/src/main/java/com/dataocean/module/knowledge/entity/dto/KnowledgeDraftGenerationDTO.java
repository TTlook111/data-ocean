package com.dataocean.module.knowledge.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 核对后提交的单篇文档职责和主动治理范围。
 */
public record KnowledgeDraftGenerationDTO(
        /**
         * 原规划中的建议序号，从零开始。
         */
        @NotNull @PositiveOrZero Integer suggestionIndex,

        /**
         * 核对后的文档名称。
         */
        @NotBlank @Size(max = 120) String title,

        /**
         * 本次整理的业务用途。
         */
        @NotBlank @Size(max = 400) String purpose,

        /**
         * 原规划中的主动依据序号，必要依赖由后端展开。
         */
        @NotEmpty @Size(max = 24) List<@NotNull @Positive Integer> basis) {}
