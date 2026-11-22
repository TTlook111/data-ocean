package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 分派人确认当前事项版本、有效负责人和分派依据。 */
public record AssignIssueDTO(
        @Positive int expectedVersion,
        @NotBlank String assigneeId,
        @NotBlank String direction,
        @NotBlank @Size(max = 1000) String reason) {}
