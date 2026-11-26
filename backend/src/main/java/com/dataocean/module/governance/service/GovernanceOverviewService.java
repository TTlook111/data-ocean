package com.dataocean.module.governance.service;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO.Detail;

/**
 * 读取治理指标及同一范围内的原记录。
 */
public interface GovernanceOverviewService {

    /**
     * 按业务日期统计确认及期末治理。
     */
    GovernanceOverviewVO overview(OverviewQuery query);

    /**
     * 分页返回用于核对原统计数字的记录。
     */
    PageVO<Detail> details(OverviewQuery query);
}
