package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 合并前核对主从事项及两端当前版本的提交提案。
 */
public record MergeFeedbackIssueDTO(
        /**
         * 待合并原事项版本。
         */
        @Positive int expectedVersion,
        /**
         * 同源主事项编号。
         */
        @NotBlank String targetId,
        /**
         * 核对来源与范围时所见的主事项版本。
         */
        @Positive int targetVersion,
        /**
         * 已核实根因及处理范围一致的依据。
         */
        @NotBlank @Size(max = 1000) String reason) {}
