package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 核查依据必须填写，原因未明时允许留空。 */
public record ReviewIssueDTO(
        @Positive int expectedVersion, @NotBlank @Size(max = 1000) String basis, @Size(max = 500) String rootCause) {}
