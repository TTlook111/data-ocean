package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 主负责人在核查后向确切原问请求缺失的业务信息。
 */
public record RequestFeedbackSupplementDTO(
        /**
         * 提交前所见的主事项版本。
         */
        @Positive int expectedVersion,
        /**
         * 需要补充的原问编号，由服务端核对其所属事项及原提问人。
         */
        @NotBlank @Size(max = 36) String requestId,
        /**
         * 只包含原提问人需要补充的具体信息。
         */
        @NotBlank @Size(max = 1000) String prompt) {}
