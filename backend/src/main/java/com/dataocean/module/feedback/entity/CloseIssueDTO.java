package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 结案前确认原问接收人及服务端产生的受控回告。 */
public record CloseIssueDTO(
        @Positive int expectedVersion,
        @NotBlank String outcome,
        @Size(max = 24) String reason,
        @NotBlank @Size(max = 1000) String conclusion,
        @NotBlank @Size(max = 1000) String basis,
        @NotBlank String recipientId,
        @NotBlank String confirmedTemplate,
        /**
         * 多原问预览的接收人及模板确认值，单原问仍采用上面的字段。
         */
        @Size(max = 36) String confirmedRecipients) {}
