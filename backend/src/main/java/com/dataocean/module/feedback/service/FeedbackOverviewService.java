package com.dataocean.module.feedback.service;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Detail;

/**
 * 提供源范围受控的反馈趋势和对账明细。
 */
public interface FeedbackOverviewService {

    /**
     * 以当前权限与统计截止时间读取各区间。
     */
    FeedbackOverviewVO overview(OverviewQuery query);

    /**
     * 以相同口径分页查看指定指标的贡献记录。
     */
    PageVO<Detail> details(OverviewQuery query);
}
