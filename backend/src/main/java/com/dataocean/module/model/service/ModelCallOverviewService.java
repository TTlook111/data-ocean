package com.dataocean.module.model.service;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.model.entity.vo.ModelCallOverviewVO;
import com.dataocean.module.model.entity.vo.ModelCallVO;

/**
 * 模型调用汇总和具有相同范围的原记录查询。
 */
public interface ModelCallOverviewService {

    /**
     * 读取同一快照下的趋势、用量排行和调用结果。
     */
    ModelCallOverviewVO overview(OverviewQuery query);

    /**
     * 可按图表传入的期间和调用结果分页回看诊断。
     */
    PageVO<ModelCallVO> calls(OverviewQuery query);
}
