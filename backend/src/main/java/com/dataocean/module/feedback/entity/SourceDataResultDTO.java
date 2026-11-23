package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 已在源系统生效的数据修正记录，供具备来源授权的人员复验。
 */
public record SourceDataResultDTO(
        /**
         * 保存时所见的事项版本。
         */
        @Positive int expectedVersion,
        /**
         * 实际操作源系统的人员或团队，区别于平台跟进人。
         */
        @NotBlank @Size(max = 120) String repairer,
        /**
         * 实际修正的数据范围和内容。
         */
        @NotBlank @Size(max = 1000) String summary,
        /**
         * 处理人只读复查所用方式与实际结果。
         */
        @NotBlank @Size(max = 1000) String checkMethod,
        /**
         * 可关联的工单、批次或其他既有记录。
         */
        @Size(max = 500) String reference,
        /**
         * 确认修正和必要的同步已生效。
         */
        boolean effectiveConfirmed) {}
