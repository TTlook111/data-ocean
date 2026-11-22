package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 当前技术负责人提交已生效的处理结果与可独立复查的记录。 */
public record SubmitIssueResultDTO(
        @Positive int expectedVersion,
        @NotBlank String impact,
        @NotBlank @Size(max = 1000) String summary,
        @NotBlank @Size(max = 1000) String checkMethod,
        @Size(max = 500) String reference,
        boolean effectiveConfirmed,
        @Size(max = 1000) String regression,
        Boolean regressionPassed) {}
