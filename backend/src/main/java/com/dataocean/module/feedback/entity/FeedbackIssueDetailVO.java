package com.dataocean.module.feedback.entity;

import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.query.service.QueryProtocol.FeedbackQuestion;

import java.time.Instant;
import java.util.List;

/** 事项工作区包含基础责任、实际可用操作和单独核验的业务证据。 */
public record FeedbackIssueDetailVO(
        IssueRow issue,
        int version,
        String direction,
        boolean canAssign,
        boolean canReview,
        boolean evidenceRestricted,
        FeedbackQuestion question,
        List<Report> reports,
        Activity latestReview,
        List<Activity> activities) {

    /** 附于原事项的点踩历史，原因到期时只保留时间和引用。 */
    public record Report(String id, String reason, Instant occurredAt) {}

    /** 操作历史保留本人、操作、版本和时间，正文仅向有证据权的人员披露。 */
    public record Activity(
            int version,
            String action,
            String actor,
            String assignee,
            String direction,
            String note,
            String rootCause,
            Instant occurredAt) {}
}
