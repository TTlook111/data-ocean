package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 记录技术修复或源数据修正期间的实际跟进，等待期间仍保留办理责任。
 */
public record FeedbackProgressDTO(
        /**
         * 保存时所见的事项版本。
         */
        @Positive int expectedVersion,
        /**
         * 当前是否在等待技术修复生效、源系统修正或同步。
         */
        boolean waitingExternal,
        /**
         * 已完成的排查、沟通或核对工作。
         */
        @NotBlank @Size(max = 1000) String workDone,
        /**
         * 后续核对或跟进安排。
         */
        @NotBlank @Size(max = 1000) String nextStep) {}
