package com.dataocean.module.fieldtag.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.fieldtag.entity.dto.FeedbackReviewRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;
import com.dataocean.module.fieldtag.service.FeedbackReviewService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈审核控制器
 * <p>
 * 提供管理员对负向反馈的审核 API：列表查看、通过、驳回。
 * 需要 field-tag:manage 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/feedback-reviews")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('field-tag:manage')")
@AdminAuditLog
@Slf4j
public class FeedbackReviewController {

    private final FeedbackReviewService feedbackReviewService;

    /**
     * 分页查询待审核反馈列表
     *
     * @param page     页码（默认 1）
     * @param pageSize 每页大小（默认 20）
     * @return 分页反馈列表
     */
    @GetMapping
    public Result<Page<FeedbackVO>> listPendingReviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(feedbackReviewService.listPendingReviews(page, pageSize));
    }

    /**
     * 审核通过
     *
     * @param feedbackId 反馈ID
     * @param request    审核请求参数
     * @return 操作结果
     */
    @PostMapping("/{feedbackId}/approve")
    public Result<Void> approveFeedback(@PathVariable Long feedbackId,
                                        @RequestBody(required = false) FeedbackReviewRequestDTO request) {
        String comment = request != null ? request.getReviewComment() : null;
        feedbackReviewService.approveFeedback(feedbackId, comment);
        return Result.success("审核通过", null);
    }

    /**
     * 审核驳回
     *
     * @param feedbackId 反馈ID
     * @param request    审核请求参数
     * @return 操作结果
     */
    @PostMapping("/{feedbackId}/reject")
    public Result<Void> rejectFeedback(@PathVariable Long feedbackId,
                                       @RequestBody(required = false) FeedbackReviewRequestDTO request) {
        String comment = request != null ? request.getReviewComment() : null;
        feedbackReviewService.rejectFeedback(feedbackId, comment);
        return Result.success("审核驳回", null);
    }
}
