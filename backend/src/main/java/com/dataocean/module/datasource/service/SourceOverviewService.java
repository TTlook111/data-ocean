package com.dataocean.module.datasource.service;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO.Detail;

/**
 * 接入运行汇总与按指标对账。
 */
public interface SourceOverviewService {

    /**
     * 读取采集终态、成功耗时及期末待核实走势。
     */
    SourceOverviewVO overview(OverviewQuery query);

    /**
     * 在相同来源、期间和截止时刻下分页读回贡献记录。
     */
    PageVO<Detail> details(OverviewQuery query);
}
