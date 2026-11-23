package com.dataocean.module.feedback.entity;

import com.dataocean.module.feedback.entity.vo.FeedbackNoticeVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 本人评价、原命令回执和后台基础队列的对外契约。
 */
public final class FeedbackProtocol {

    /** 协议类型不持有业务状态。 */
    private FeedbackProtocol() {}

    /** 评价修改必须明确提交所见版本，首次评价传空。 */
    public record RatingCommand(String action, String reason, @JsonProperty(required = true) Integer expectedVersion) {}

    /** 已提交的原命令只返回安全引用，处理进度另行回读。 */
    public record Receipt(
            String commandId, String eventId, String answerId, int answerRevision, int version, Instant acceptedAt) {}

    /** 原用户在当前有效回答上可见的评价及事实入队状态。 */
    public record Rating(
            String answerId,
            int answerRevision,
            String rating,
            String reason,
            Integer version,
            Instant updatedAt,
            String queueState,
            String issueId,
            FeedbackNoticeVO notice) {}

    /** 后台队列的基础投影，不含原问题或用户原因。 */
    public record IssueRow(
            String id,
            String sourceId,
            String sourceName,
            String origin,
            String status,
            String assigneeId,
            String assigneeName,
            Instant createdAt,
            Instant updatedAt,
            String summary,
            int referenceCount,
            String nextAction) {}
}
