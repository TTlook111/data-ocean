package com.dataocean.module.feedback.controller;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Rating;
import com.dataocean.module.feedback.entity.FeedbackProtocol.RatingCommand;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Receipt;
import com.dataocean.module.feedback.service.FeedbackService;
import com.dataocean.module.iam.security.RequirePermission;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** 反馈服务维护独立的资格、提交及队列状态。 */
    private final FeedbackService feedback;

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

    /** 后台列表仅展示已持久入队的基本事实。 */
    @GetMapping("/api/v1/feedback/issues")
    @RequirePermission("feedback.view")
    public Result<PageVO<IssueRow>> issues(
            @ModelAttribute PageQuery page,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String issueId) {
        return Result.success(feedback.issues(page, sourceId, issueId));
    }
}
