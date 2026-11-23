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
        Resolution resolution,
        /**
         * 当前事项内的独立处理任务，单任务旧流程使用事项本身办理。
         */
        List<FeedbackTaskVO> tasks,
        /**
         * 用户补充、合并与重开的当前事实。
         */
        FeedbackLifecycleVO lifecycle) {

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
            Instant occurredAt,
            /**
             * 有关联任务时展示事项内的稳定任务编号。
             */
            Integer taskId,
            /**
             * 合并或纠正时的关系另一端。
             */
            String relatedIssueId,
            /**
             * 写入事件时的完成轮次。
             */
            int completionRound,
            /**
             * 本轮结案的结构化结果，沿用结案时固定的原始模板编码。
             */
            String closureCode) {}

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
            String recheckRequestId,
            /**
             * 当前源数据任务跟进人可保存进展或已生效的修正结果。
             */
            boolean canSaveSourceData) {}

    /**
     * 已提交结果及当时适用的发布、实际修正责任和影响范围。
     */
    public record ProcessingResult(
            int version,
            String impact,
            String summary,
            String checkMethod,
            String reference,
            String regression,
            Instant submittedAt,
            String knowledgeBindingId,
            /**
             * 源系统修正人或团队，按来源证据权限裁剪。
             */
            String sourceRepairer) {}

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
            String recheckRequestId,
            /**
             * 是否关联了实际问数，问数编号仍仅向该轮执行人披露。
             */
            boolean questionReplay) {}

    /** 回告的收件与阅读状态与事项结案分开。 */
    public record NoticeStatus(String state, Instant sentAt, Instant readAt) {}
}
