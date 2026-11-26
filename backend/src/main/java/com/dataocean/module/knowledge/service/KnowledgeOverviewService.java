package com.dataocean.module.knowledge.service;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO.Detail;

/**
 * 知识概览及按原范围和时点回读明细。
 */
public interface KnowledgeOverviewService {

    /** 读取正式可用性及期间发生量。 */
    KnowledgeOverviewVO overview(OverviewQuery query);

    /** 以相同日期和截止时间返回贡献记录。 */
    PageVO<Detail> details(OverviewQuery query);
}
