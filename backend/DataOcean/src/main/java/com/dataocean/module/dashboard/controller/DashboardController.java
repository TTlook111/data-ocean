package com.dataocean.module.dashboard.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.dashboard.entity.vo.DashboardStatsVO;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页看板控制器。
 * <p>
 * 聚合用户、数据源、快照、治理问题和近期活动等概览指标。
 * 权限判定改为 IAM-SIMPLE-1：入口要求全局功能 `admin:workbench:view`，
 * 各卡片再由 Service 按对应域的负责源裁剪，不再依赖旧的全权限标识。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取管理端首页统计数据。
     *
     * @return 首页统计视图（按调用者的功能与负责源裁剪）
     */
    @GetMapping("/stats")
    @IamS1Global("admin:workbench:view")
    public Result<DashboardStatsVO> getStats() {
        // 入口准入由 @IamS1Global 承担；每张卡片仍由 Service 按所属域功能与负责源分别裁剪，
        // 入口注解不代表调用者自动拥有所有卡片的数据。
        return Result.success(dashboardService.getStats(UserContext.currentUserId()));
    }
}
