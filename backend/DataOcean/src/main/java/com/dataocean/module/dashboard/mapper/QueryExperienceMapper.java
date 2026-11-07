package com.dataocean.module.dashboard.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.dashboard.entity.po.QueryExperienceCounts;
import com.dataocean.module.dashboard.entity.po.QueryExperienceVotes;
import com.dataocean.module.dashboard.entity.query.QueryExperienceScopeQuery;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceDetailVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 数据库完成分桶、分位数定位及分页，不把全体问数记录或回答正文搬入应用。 */
@Mapper
public interface QueryExperienceMapper {
    List<QueryExperienceCounts> buckets(@Param("q") QueryExperienceScopeQuery scope);
    Double average(@Param("q") QueryExperienceScopeQuery scope);
    Long rankedDuration(@Param("q") QueryExperienceScopeQuery scope, @Param("offset") long offset);
    QueryExperienceVotes votes(@Param("q") QueryExperienceScopeQuery scope);

    Page<QueryExperienceDetailVO> slowPage(Page<QueryExperienceDetailVO> page,
                                           @Param("q") QueryExperienceScopeQuery scope);
    Page<QueryExperienceDetailVO> dislikePage(Page<QueryExperienceDetailVO> page,
                                              @Param("q") QueryExperienceScopeQuery scope);
}
