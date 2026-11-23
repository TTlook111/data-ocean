package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 原提问人补充业务说明时提交的内容。
 */
public record SubmitFeedbackSupplementDTO(
        /**
         * 所见原事项版本，合并或管理员再次请求后须重新核对。
         */
        @Positive int expectedVersion,
        /**
         * 待补充的请求编号，主动补充知识缺口时为空。
         */
        @Size(max = 36) String supplementId,
        /**
         * 用户原始说明，作为待核实证据保存。
         */
        @NotBlank @Size(max = 1000) String answer) {}
