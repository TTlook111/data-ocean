package com.dataocean.module.datasource.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO.Detail;
import com.dataocean.module.datasource.service.SourceOverviewService;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 接入概览与采集、结构变化的统计对账入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/datasources/overview")
public class SourceOverviewController {

    /**
     * 统计读口径与下钻共用的业务服务。
     */
    private final SourceOverviewService overview;

    /**
     * 按已授权的后台接入统计权限读取汇总。
     */
    @GetMapping
    @RequirePermission("sources-dashboard.view")
    public Result<SourceOverviewVO> overview(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.overview(query));
    }

    /**
     * 任务和结构变化明细继续要求采集记录查看权。
     */
    @GetMapping("/details")
    @RequirePermission({"sources-dashboard.view", "collections.view"})
    public Result<PageVO<Detail>> details(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.details(query));
    }
}
