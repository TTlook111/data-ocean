package com.dataocean.module.feedback.controller;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Rating;
import com.dataocean.module.feedback.entity.FeedbackProtocol.RatingCommand;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Receipt;
import com.dataocean.module.feedback.entity.dto.AssignIssueDTO;
import com.dataocean.module.feedback.entity.dto.ChangeFeedbackTaskDTO;
import com.dataocean.module.feedback.entity.dto.CloseIssueDTO;
import com.dataocean.module.feedback.entity.dto.DisposeFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackIssueReasonDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.dto.MergeFeedbackIssueDTO;
import com.dataocean.module.feedback.entity.dto.RequestFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.dto.SourceDataResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitKnowledgeResultDTO;
import com.dataocean.module.feedback.entity.dto.VerifyIssueDTO;
import com.dataocean.module.feedback.entity.query.FeedbackIssueQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackIssueDetailVO;
import com.dataocean.module.feedback.entity.vo.FeedbackNoticeVO;
import com.dataocean.module.feedback.entity.vo.FeedbackSupplementVO;
import com.dataocean.module.feedback.service.FeedbackIssueLifecycleService;
import com.dataocean.module.feedback.service.FeedbackIssueQueryService;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.feedback.service.FeedbackService;
import com.dataocean.module.feedback.service.FeedbackWorkService;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.query.entity.vo.QueryAcceptedVO;
import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 已交付回答的评价与后台已入队事项。
 */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    /** 按回答和原命令回查评价资源。 */
    private static final String RATING = "/api/v1/query/requests/{answerId}/answers/{answerRevision}/rating";

    /**
     * 用户补充从原问打开，只读取该原问本人的反馈事实。
     */
    private static final String QUESTIONS_SUPPLEMENT = "/api/v1/query/requests/{id}/feedback-supplements";

    /** 反馈服务维护独立的资格、提交及队列状态。 */
    private final FeedbackService feedback;

    /**
     * 队列筛选、负责人候选和详情证据读取。
     */
    private final FeedbackIssueQueryService issueQuery;

    /**
     * 原问题补充、重复事项关系和结案重开。
     */
    private final FeedbackIssueLifecycleService lifecycle;

    /**
     * 分派、核查、结果和复验结案。
     */
    private final FeedbackWorkService work;

    /** 站内回告从同一本人收件箱读取。 */
    private final FeedbackNoticeService notices;

    /** 读取本人当前评价。 */
    @GetMapping(RATING)
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<Rating> rating(@PathVariable String answerId, @PathVariable int answerRevision) {
        return Result.success(feedback.rating(answerId, answerRevision));
    }

    /** 成功接收点踩不等于后台已创建事项。 */
    @PostMapping(RATING)
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public ResponseEntity<Result<Receipt>> rate(
            @PathVariable String answerId,
            @PathVariable int answerRevision,
            @RequestHeader("Idempotency-Key") String commandId,
            @RequestBody RatingCommand command) {
        var receipt = feedback.submit(answerId, answerRevision, commandId, command);
        return ResponseEntity.status("DISLIKE".equals(command.action()) ? 202 : 200)
                .body(Result.success(receipt));
    }

    /** 原回包不明时核对同一评价命令。 */
    @GetMapping(RATING + "/commands/{commandId}")
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<Receipt> receipt(
            @PathVariable String answerId, @PathVariable int answerRevision, @PathVariable String commandId) {
        return Result.success(feedback.receipt(answerId, answerRevision, commandId));
    }

    /** 后台列表按本人的实际办理资格或负责人范围查询。 */
    @GetMapping("/api/v1/feedback/issues")
    @RequirePermission("feedback.view")
    public Result<PageVO<IssueRow>> issues(@Valid @ModelAttribute FeedbackIssueQuery query) {
        return Result.success(issueQuery.issues(query));
    }

    /** 队列与筛选选项共用后台事项查看权限。 */
    @GetMapping("/api/v1/feedback/sources")
    @RequirePermission("feedback.view")
    public Result<List<QuerySourceOptionVO>> sources() {
        return Result.success(issueQuery.sources());
    }

    /** 详情只向当前有权查看的人提供对应业务投影。 */
    @GetMapping("/api/v1/feedback/issues/{id}")
    @RequirePermission("feedback.view")
    public Result<FeedbackIssueDetailVO> detail(@PathVariable String id) {
        return Result.success(issueQuery.detail(id));
    }

    /**
     * 主负责人请求原提问人补充具体信息。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/supplement-requests")
    @RequirePermission("feedback.process")
    public Result<MutationVO> askSupplement(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody RequestFeedbackSupplementDTO input) {
        return Result.success(lifecycle.askSupplement(id, command, input));
    }

    /**
     * 主负责人撤回不再需要的请求，或按实际原因结束等待。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/supplement-requests/{supplementId}/dispositions")
    @RequirePermission("feedback.process")
    public Result<MutationVO> disposeSupplement(
            @PathVariable String id,
            @PathVariable String supplementId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody DisposeFeedbackSupplementDTO input) {
        return Result.success(lifecycle.disposeSupplement(id, supplementId, command, input));
    }

    /**
     * 从本人原问读取当前补充能力和已有说明。
     */
    @GetMapping(QUESTIONS_SUPPLEMENT)
    @RequirePermission("query.view")
    public Result<FeedbackSupplementVO> ownSupplement(@PathVariable String id) {
        return Result.success(lifecycle.ownSupplement(id));
    }

    /**
     * 用户回复的正文回写原事项证据。
     */
    @PostMapping(QUESTIONS_SUPPLEMENT)
    @RequirePermission("query.view")
    public Result<MutationVO> submitSupplement(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SubmitFeedbackSupplementDTO input) {
        return Result.success(lifecycle.submitSupplement(id, command, input));
    }

    /**
     * 对当前确认的同源重复事项建立主从关系。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/merges")
    @RequirePermission("feedback.assign")
    public Result<MutationVO> merge(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody MergeFeedbackIssueDTO input) {
        return Result.success(lifecycle.merge(id, command, input));
    }

    /**
     * 已关联的原事项可凭依据纠正合并。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/merge-corrections")
    @RequirePermission("feedback.assign")
    public Result<MutationVO> unmerge(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody FeedbackIssueReasonDTO input) {
        return Result.success(lifecycle.unmerge(id, command, input));
    }

    /**
     * 按新证据为已结案事项开启下一轮处理。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/reopens")
    @RequirePermission("feedback.process")
    public Result<MutationVO> reopen(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody FeedbackIssueReasonDTO input) {
        return Result.success(lifecycle.reopen(id, command, input));
    }

    /** 可交接的负责人由实际角色决定，支持标准分页和搜索。 */
    @GetMapping("/api/v1/feedback/assignees")
    @RequirePermission("feedback.assign")
    public Result<PageVO<UserOptionVO>> candidates(
            @ModelAttribute PageQuery page, @RequestParam(required = false) @Size(max = 80) String keyword) {
        return Result.success(issueQuery.candidates(page, keyword));
    }

    /** 分派时将事项目标和原命令身份一起提交。 */
    @PostMapping("/api/v1/feedback/issues/{id}/assignments")
    @RequirePermission("feedback.assign")
    public Result<MutationVO> assign(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody AssignIssueDTO input) {
        return Result.success(work.assign(id, command, input));
    }

    /**
     * 在当前主事项下添加独立的治理、技术或源数据任务。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/tasks")
    @RequirePermission("feedback.assign")
    public Result<MutationVO> addTask(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody AssignIssueDTO input) {
        return Result.success(work.assignTask(id, null, command, input));
    }

    /**
     * 按任务交接办理责任，主事项负责人沿用原入口维护。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/tasks/{task}/assignments")
    @RequirePermission("feedback.assign")
    public Result<MutationVO> assignTask(
            @PathVariable String id,
            @PathVariable @Positive int task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody AssignIssueDTO input) {
        return Result.success(work.assignTask(id, task, command, input));
    }

    /**
     * 退出或恢复任务时由服务端再确认协调权限和事项责任。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/tasks/{task}/dispositions")
    @RequirePermission(anyOf = {"feedback.assign", "feedback.process"})
    public Result<MutationVO> changeTaskStatus(
            @PathVariable String id,
            @PathVariable @Positive int task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody ChangeFeedbackTaskDTO input) {
        return Result.success(work.changeTaskStatus(id, task, command, input));
    }

    /**
     * 保存源数据跟进进展和下一步，任务继续在办。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/tasks/{task}/source-progress")
    @RequirePermission("feedback.process")
    public Result<MutationVO> followUpSourceData(
            @PathVariable String id,
            @PathVariable @Positive int task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody FeedbackProgressDTO input) {
        return Result.success(work.followUpSourceData(id, task, command, input));
    }

    /**
     * 技术负责人记录实际排查或等待修复生效，可撤回当前待验证结果继续跟进。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/technical-progress")
    @RequirePermission("feedback.process")
    public Result<MutationVO> followUpTechnical(
            @PathVariable String id,
            @RequestParam(required = false) Integer task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody FeedbackProgressDTO input) {
        return Result.success(work.followUpTechnical(id, task, command, input));
    }

    /**
     * 记录已生效的源数据修正和只读复查依据，交由有权人员复验。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/tasks/{task}/source-results")
    @RequirePermission("feedback.process")
    public Result<MutationVO> submitSourceData(
            @PathVariable String id,
            @PathVariable @Positive int task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SourceDataResultDTO input) {
        return Result.success(work.submitSourceData(id, task, command, input));
    }

    /** 负责人提交核查依据，事务内重新确认当前责任。 */
    @PostMapping("/api/v1/feedback/issues/{id}/reviews")
    @RequirePermission("feedback.process")
    public Result<MutationVO> review(
            @PathVariable String id,
            @RequestParam(required = false) @Positive Integer task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody ReviewIssueDTO input) {
        return Result.success(work.review(id, task, command, input));
    }

    /** 从当前分派和核查提交实际技术处理结果。 */
    @PostMapping("/api/v1/feedback/issues/{id}/results")
    @RequirePermission("feedback.process")
    public Result<MutationVO> submitResult(
            @PathVariable String id,
            @RequestParam(required = false) @Positive Integer task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SubmitIssueResultDTO input) {
        return Result.success(work.submitResult(id, task, command, input));
    }

    /** 关联已经正式上线的治理结果，不在反馈模块另行发布知识。 */
    @PostMapping("/api/v1/feedback/issues/{id}/knowledge-results")
    @RequirePermission("feedback.process")
    public Result<MutationVO> submitKnowledge(
            @PathVariable String id,
            @RequestParam(required = false) @Positive Integer task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SubmitKnowledgeResultDTO input) {
        return Result.success(work.submitKnowledge(id, task, command, input));
    }

    /** 同一原问题按当前复验人的权限实际派发。 */
    @PostMapping("/api/v1/feedback/issues/{id}/rechecks")
    @RequirePermission("feedback.verify")
    public Result<QueryAcceptedVO> replayQuestion(
            @PathVariable String id,
            @RequestParam(required = false) @Positive Integer task,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(work.replayQuestion(id, task, command));
    }

    /** 人工复验固定实际操作人、来源权限和当前结果版本。 */
    @PostMapping("/api/v1/feedback/issues/{id}/verifications")
    @RequirePermission("feedback.verify")
    public Result<MutationVO> verify(
            @PathVariable String id,
            @RequestParam(required = false) @Positive Integer task,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody VerifyIssueDTO input) {
        return Result.success(work.verify(id, task, command, input));
    }

    /** 结案前只读核对受控回告和原接收人。 */
    @GetMapping("/api/v1/feedback/issues/{id}/closure-preview")
    @RequirePermission("feedback.close")
    public Result<FeedbackClosurePreviewVO> closurePreview(
            @PathVariable String id, @RequestParam String outcome, @RequestParam(required = false) String reason) {
        return Result.success(work.closurePreview(id, outcome, reason));
    }

    /** 成功结案时接收站内任务，实际交付异步确认。 */
    @PostMapping("/api/v1/feedback/issues/{id}/closures")
    @RequirePermission("feedback.close")
    public Result<MutationVO> close(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody CloseIssueDTO input) {
        return Result.success(work.close(id, command, input));
    }

    /** 问数失权后仍可读取本人的通用反馈提示。 */
    @GetMapping("/api/v1/feedback/notices")
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<PageVO<FeedbackNoticeVO>> inbox(@ModelAttribute PageQuery page) {
        return Result.success(notices.inbox(page));
    }

    /** 已读限定当前用户，稳定消息编号天然防止重复写入。 */
    @PutMapping("/api/v1/feedback/notices/{id}/read")
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<FeedbackNoticeVO> read(@PathVariable String id) {
        return Result.success(notices.read(id));
    }
}
