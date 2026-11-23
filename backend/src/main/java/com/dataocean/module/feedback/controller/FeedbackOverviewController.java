package com.dataocean.module.feedback.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.result.Result;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Detail;
import com.dataocean.module.feedback.service.FeedbackOverviewService;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 反馈概览和有独立查看资格的对账明细。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/feedback/overview")
public class FeedbackOverviewController {

    /**
     * 汇总与下钻使用同一套来源和时间口径。
     */
    private final FeedbackOverviewService overview;

    /**
     * 读取当前账号获准数据源的反馈统计。
     */
    @GetMapping
    @RequirePermission("feedback-dashboard.view")
    public Result<FeedbackOverviewVO> overview(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.overview(query));
    }

    /**
     * 对账需要同时具备概览及问题队列查看权限。
     */
    @GetMapping("/details")
    @RequirePermission({"feedback-dashboard.view", "feedback.view"})
    public Result<PageVO<Detail>> details(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.details(query));
    }
}
