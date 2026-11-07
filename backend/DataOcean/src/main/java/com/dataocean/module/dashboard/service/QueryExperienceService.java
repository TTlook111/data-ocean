package com.dataocean.module.dashboard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.dashboard.entity.query.QueryExperienceDetailsQuery;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceDetailVO;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceVO;

/** 工作台问数体验只读接口，摘要与明细共用创建时间口径和各域负责源。 */
public interface QueryExperienceService {
    QueryExperienceVO summary(Long userId, String period);
    Page<QueryExperienceDetailVO> details(Long userId, QueryExperienceDetailsQuery query);
}
