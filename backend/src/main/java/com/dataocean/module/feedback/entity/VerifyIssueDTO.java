package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 复验者登记当前生效场景的期望、实际结果和核对依据。 */
public record VerifyIssueDTO(
        @Positive int expectedVersion,
        @NotBlank String decision,
        @NotBlank @Size(max = 1000) String testScope,
        @NotBlank @Size(max = 500) String expected,
        @NotBlank @Size(max = 1000) String actual,
        @NotBlank @Size(max = 1000) String dataBasis) {}
