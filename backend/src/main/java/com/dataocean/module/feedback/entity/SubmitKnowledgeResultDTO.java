package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 负责人把知识修订及其覆盖原问题的依据提交给复验人。 */
public record SubmitKnowledgeResultDTO(
        @Positive int expectedVersion,
        @NotBlank String bindingId,
        @NotBlank @Size(max = 1000) String summary,
        @NotBlank @Size(max = 1000) String checkMethod) {}
