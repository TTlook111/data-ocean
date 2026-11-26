package com.dataocean.module.dashboard.service;

import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.dashboard.entity.vo.DashboardOverviewVO;

/**
 * 按当前角色和来源返回可对账的运营总览。
 */
public interface DashboardOverviewService {

    /**
     * 在同一快照组合正式问数及已有模块统计。
     */
    DashboardOverviewVO overview(OverviewQuery query);
}
