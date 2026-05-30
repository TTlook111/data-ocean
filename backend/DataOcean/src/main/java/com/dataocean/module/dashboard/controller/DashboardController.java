package com.dataocean.module.dashboard.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.dashboard.entity.vo.DashboardStatsVO;
import com.dataocean.module.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页看板控制器。
 * <p>
 * 聚合用户、数据源、快照、治理问题和近期活动等概览指标。
 * 仅管理员（拥有全权限标识）可访问，避免普通登录用户绕过前端隐藏直接获取全站统计。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('*')")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取管理端首页统计数据。
     *
     * @return 首页统计视图
     */
    @GetMapping("/stats")
    public Result<DashboardStatsVO> getStats() {
        return Result.success(dashboardService.getStats());
    }
}
