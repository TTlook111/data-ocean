package com.dataocean.module.dashboard.service;

import com.dataocean.module.dashboard.entity.vo.DashboardStatsVO;

/**
 * 管理端首页看板服务。
 */
public interface DashboardService {

    /**
     * 汇总首页统计指标。
     *
     * @return 首页统计视图
     */
    DashboardStatsVO getStats();
}
