package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QueryLineageColumn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询血缘-字段级关系 Mapper 接口
 */
@Mapper
public interface QueryLineageColumnMapper extends BaseMapper<QueryLineageColumn> {
}
