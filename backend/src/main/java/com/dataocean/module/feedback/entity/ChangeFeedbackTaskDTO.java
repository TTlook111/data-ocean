package com.dataocean.module.feedback.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 协调者对处理任务办理退出或恢复时提交的事实。
 */
public record ChangeFeedbackTaskDTO(
        /**
         * 当前主事项版本，并发处理后须重新读取。
         */
        @Positive int expectedVersion,
        /**
         * 无需变更、取消或恢复为在办。
         */
        @NotBlank @Size(max = 16) String status,
        /**
         * 退出或恢复的依据。
         */
        @NotBlank @Size(max = 1000) String reason,
        /**
         * 退出时说明剩余影响及跟进安排。
         */
        @Size(max = 1000) String remainingImpact,
        /**
         * 退出时确认没有未覆盖的影响。
         */
        boolean remainingImpactCovered) {}
