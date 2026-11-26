package com.dataocean.module.feedback.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 原提问人可读的受控回告，失权时不返回原问定位。
 */
@Data
@AllArgsConstructor
public class FeedbackNoticeVO {

    /**
     * 本次站内回告的稳定编号。
     */
    private String id;

    /**
     * 按照当前接收资格生成的受控回告正文。
     */
    private String message;

    /**
     * 原问来源编号，权限受限时为空。
     */
    private String sourceId;

    /**
     * 对应原问题编号。
     */
    private String requestId;

    /**
     * 实际投递时刻，仍待投递时为空。
     */
    private Instant sentAt;

    /**
     * 收件人首次确认已读的时刻。
     */
    private Instant readAt;

    /**
     * 该接收人在本轮回告下当前有权定位的各条原问题。
     */
    private List<String> requestIds;
}
