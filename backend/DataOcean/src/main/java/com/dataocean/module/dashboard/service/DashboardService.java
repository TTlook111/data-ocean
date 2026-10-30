package com.dataocean.module.dashboard.service;

import com.dataocean.module.dashboard.entity.vo.DashboardStatsVO;

/**
 * 管理端首页看板服务。
 */
public interface DashboardService {

    /**
     * 汇总首页统计指标。
     *
     * <p>每个卡片按其所属域的 S1 功能码与负责源裁剪：没有该功能码时该卡片归零，
     * 有则只统计调用者负责的数据源。工作台本身要求全局功能 `admin:workbench:view`。</p>
     *
     * @param userId 调用者
     * @return 首页统计视图
     */
    DashboardStatsVO getStats(Long userId);
}
