package com.dataocean.module.dashboard.controller;

import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.result.Result;
import com.dataocean.module.dashboard.entity.vo.DashboardOverviewVO;
import com.dataocean.module.dashboard.service.DashboardOverviewService;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 提供后台总览的统一读取入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard/overview")
public class DashboardOverviewController {

    /**
     * 统一统计范围与截止时刻的业务服务。
     */
    private final DashboardOverviewService dashboard;

    /**
     * 读取当前账号可查看的趋势和期末存量。
     */
    @GetMapping
    @RequirePermission("dashboard.view")
    public Result<DashboardOverviewVO> overview(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(dashboard.overview(query));
    }
}
