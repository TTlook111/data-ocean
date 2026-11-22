package com.dataocean.module.feedback.entity;

import java.time.Instant;

/** 原提问人可读的受控回告，失权时不返回原问定位。 */
public record FeedbackNoticeVO(
        String id, String message, String sourceId, String requestId, Instant sentAt, Instant readAt) {}
