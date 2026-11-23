package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 主负责人对一条仍在等待的补充请求作出的处置。
 */
public record DisposeFeedbackSupplementDTO(
        /**
         * 所见主事项版本，原提问人回复或关联变化后须重新核对。
         */
        @Positive int expectedVersion,
        /**
         * 撤回不再需要的请求，或记录无法取得补充的事实。
         */
        @NotBlank @Pattern(regexp = "WITHDRAWN|UNAVAILABLE") String disposition,
        /**
         * 处置依据，只在有权读取该原问题证据的后台投影中显示。
         */
        @NotBlank @Size(max = 1000) String reason) {}
