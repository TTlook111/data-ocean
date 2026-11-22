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
        List<QueryIncident> incidents,
        Activity latestReview,
        List<Activity> activities,
        Resolution resolution) {

    /** 附于原事项的点踩历史，原因到期时只保留时间和引用。 */
    public record Report(String id, String reason, Instant occurredAt) {}

    /** 只包含受控诊断编码和原问身份，原文另行裁剪。 */
    public record QueryIncident(
            String requestId, String state, String phase, String diagnostic, String executionState, Instant failedAt) {}

    /** 操作历史保留本人、操作、版本和时间，正文仅向有证据权的人员披露。 */
    public record Activity(
            int version,
            String action,
            String actor,
            String assignee,
            String direction,
            String note,
            String rootCause,
            String conclusion,
            Instant occurredAt) {}

    /** 当前工作结果、复验有效性和允许继续办理的权限。 */
    public record Resolution(
            boolean canSubmitResult,
            boolean canVerify,
            boolean canResolve,
            boolean canCloseOther,
            ProcessingResult result,
            Recheck verification,
            NoticeStatus notice,
            boolean canSubmitKnowledge,
            boolean canReplayQuestion,
            String recheckRequestId) {}

    /** 已提交结果与实际修复影响范围。 */
    public record ProcessingResult(
            int version,
            String impact,
            String summary,
            String checkMethod,
            String reference,
            String regression,
            Instant submittedAt,
            String knowledgeBindingId) {}

    /** 人工登记的检查与当前版本、测试授权上下文的对应关系。 */
    public record Recheck(
            int version,
            String decision,
            boolean valid,
            String testScope,
            String expected,
            String actual,
            String dataBasis,
            Instant verifiedAt,
            String recheckRequestId) {}

    /** 回告的收件与阅读状态与事项结案分开。 */
    public record NoticeStatus(String state, Instant sentAt, Instant readAt) {}
}
