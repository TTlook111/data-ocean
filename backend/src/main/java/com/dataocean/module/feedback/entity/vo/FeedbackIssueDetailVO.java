package com.dataocean.module.feedback.entity.vo;

import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.query.entity.vo.FeedbackQuestionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 事项工作区包含基础责任、实际可用操作和单独核验的业务证据。
 */
@Data
@AllArgsConstructor
public class FeedbackIssueDetailVO {

    /**
     * 与事项队列一致的基础责任和展示状态。
     */
    private IssueRow issue;

    /**
     * 当前记录所用的事项版本。
     */
    private int version;

    /**
     * 当前事项初步分流或事件涉及的办理方向。
     */
    private String direction;

    /**
     * 当前调用人能否分派该事项。
     */
    private boolean canAssign;

    /**
     * 当前人员是否具有主事项核查资格。
     */
    private boolean canReview;

    /**
     * 当前人员不能读取该事项原问正文。
     */
    private boolean evidenceRestricted;

    /**
     * 按当前授权裁剪的原问证据。
     */
    private FeedbackQuestionVO question;

    /**
     * 近期关联的点踩记录。
     */
    private List<Report> reports;

    /**
     * 自动入队的诊断记录。
     */
    private List<QueryIncident> incidents;

    /**
     * 最近一次事项核查及其可见依据。
     */
    private Activity latestReview;

    /**
     * 最近的台账动作，按事项版本倒序。
     */
    private List<Activity> activities;

    /**
     * 当期结果、复验及结案能力。
     */
    private Resolution resolution;

    /**
     * 当前事项内的独立处理任务，单任务旧流程使用事项本身办理。
     */
    private List<FeedbackTaskVO> tasks;

    /**
     * 用户补充、合并与重开的当前事实。
     */
    private FeedbackLifecycleVO lifecycle;

    /**
     * 附于原事项的点踩历史，原因到期时只保留时间和引用。
     */
    @Data
    @AllArgsConstructor
    public static class Report {

        /**
         * 关联点踩事件的稳定编号。
         */
        private String id;

        /**
         * 点踩原因或结案原因编码，无法展示时为空。
         */
        private String reason;

        /**
         * 操作发生的 UTC 时刻。
         */
        private Instant occurredAt;
    }

    /**
     * 只包含受控诊断编码和原问身份，原文另行裁剪。
     */
    @Data
    @AllArgsConstructor
    public static class QueryIncident {

        /**
         * 对应原问题编号。
         */
        private String requestId;

        /**
         * 当前处理或投递状态编码。
         */
        private String state;

        /**
         * 失败时问数所处的阶段。
         */
        private String phase;

        /**
         * 面向后台的受控诊断编码。
         */
        private String diagnostic;

        /**
         * 失败时源端只读执行情况。
         */
        private String executionState;

        /**
         * 问数产生失败线索的 UTC 时刻。
         */
        private Instant failedAt;
    }

    /**
     * 操作历史保留本人、操作、版本和时间，正文仅向有证据权的人员披露。
     */
    @Data
    @AllArgsConstructor
    public static class Activity {

        /**
         * 该动作写入台账时的事项版本。
         */
        private int version;

        /**
         * 形成该历史记录的办理动作编码。
         */
        private String action;

        /**
         * 完成该动作的人员显示名。
         */
        private String actor;

        /**
         * 动作涉及的负责人显示名。
         */
        private String assignee;

        /**
         * 当前事项初步分流或事件涉及的办理方向。
         */
        private String direction;

        /**
         * 该动作的可见依据或实际进展。
         */
        private String note;

        /**
         * 核查根因或跟进动作的下一步。
         */
        private String rootCause;

        /**
         * 该动作形成的可见结论。
         */
        private String conclusion;

        /**
         * 操作发生的 UTC 时刻。
         */
        private Instant occurredAt;

        /**
         * 有关联任务时展示事项内的稳定任务编号。
         */
        private Integer taskId;

        /**
         * 合并或纠正时的关系另一端。
         */
        private String relatedIssueId;

        /**
         * 写入事件时的完成轮次。
         */
        private int completionRound;

        /**
         * 本轮结案的结构化结果，沿用结案时固定的原始模板编码。
         */
        private String closureCode;
    }

    /**
     * 当前工作结果、复验有效性和允许继续办理的权限。
     */
    @Data
    @AllArgsConstructor
    public static class Resolution {

        /**
         * 当前技术负责人能否提交或修订结果。
         */
        private boolean canSubmitResult;

        /**
         * 当前人员能否登记复验。
         */
        private boolean canVerify;

        /**
         * 本轮复验是否支持当前人员办理已解决结案。
         */
        private boolean canResolve;

        /**
         * 当前人员能否说明并结案。
         */
        private boolean canCloseOther;

        /**
         * 当前仍生效的处理结果。
         */
        private ProcessingResult result;

        /**
         * 该结果对应的复验记录。
         */
        private Recheck verification;

        /**
         * 当前轮次的实际回告状态。
         */
        private NoticeStatus notice;

        /**
         * 当前人员能否关联新的治理发布并提交结果。
         */
        private boolean canSubmitKnowledge;

        /**
         * 是否可按本人权限重跑原问。
         */
        private boolean canReplayQuestion;

        /**
         * 当前人员有权定位的实际复验问数。
         */
        private String recheckRequestId;

        /**
         * 当前源数据任务跟进人可保存进展或已生效的修正结果。
         */
        private boolean canSaveSourceData;
    }

    /**
     * 已提交结果及当时适用的发布、实际修正责任和影响范围。
     */
    @Data
    @AllArgsConstructor
    public static class ProcessingResult {

        /**
         * 该处理结果写入时的事项版本。
         */
        private int version;

        /**
         * 该次修正的影响类别。
         */
        private String impact;

        /**
         * 实际修正范围及处理结果的可见正文。
         */
        private String summary;

        /**
         * 实际复查方式和结果。
         */
        private String checkMethod;

        /**
         * 修复或源系统工单参考。
         */
        private String reference;

        /**
         * 涉及安全或查询行为时登记的回归场景。
         */
        private String regression;

        /**
         * 当前结果的提交时刻。
         */
        private Instant submittedAt;

        /**
         * 治理结果引用的正式发布绑定。
         */
        private String knowledgeBindingId;

        /**
         * 源系统修正人或团队，按来源证据权限裁剪。
         */
        private String sourceRepairer;
    }

    /**
     * 人工登记的检查与当前版本、测试授权上下文的对应关系。
     */
    @Data
    @AllArgsConstructor
    public static class Recheck {

        /**
         * 该复验结果写入时的事项版本。
         */
        private int version;

        /**
         * 复验通过或未通过。
         */
        private String decision;

        /**
         * 该复验是否仍匹配当前结果及授权。
         */
        private boolean valid;

        /**
         * 有权查看的复验场景。
         */
        private String testScope;

        /**
         * 该轮复验的预期结果。
         */
        private String expected;

        /**
         * 该轮复验观察到的实际结果。
         */
        private String actual;

        /**
         * 记录该轮复验的数据及核对依据。
         */
        private String dataBasis;

        /**
         * 复验登记的 UTC 时刻。
         */
        private Instant verifiedAt;

        /**
         * 当前人员有权定位的实际复验问数。
         */
        private String recheckRequestId;

        /**
         * 是否关联了实际问数，问数编号仍仅向该轮执行人披露。
         */
        private boolean questionReplay;
    }

    /**
     * 回告的收件与阅读状态与事项结案分开。
     */
    @Data
    @AllArgsConstructor
    public static class NoticeStatus {

        /**
         * 当前处理或投递状态编码。
         */
        private String state;

        /**
         * 实际投递时刻，仍待投递时为空。
         */
        private Instant sentAt;

        /**
         * 收件人首次确认已读的时刻。
         */
        private Instant readAt;
    }
}
