package com.dataocean.module.feedback.entity;

import java.time.Instant;
import java.util.List;

/** 原提问人可读的受控回告，失权时不返回原问定位。 */
public record FeedbackNoticeVO(
        String id,
        String message,
        String sourceId,
        String requestId,
        Instant sentAt,
        Instant readAt,
        /**
         * 该接收人在本轮回告下当前有权定位的各条原问题。
         */
        List<String> requestIds) {}
