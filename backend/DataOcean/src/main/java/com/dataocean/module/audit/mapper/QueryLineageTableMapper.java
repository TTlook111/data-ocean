package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QueryLineageTable;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询血缘-表级关系 Mapper 接口
 */
@Mapper
public interface QueryLineageTableMapper extends BaseMapper<QueryLineageTable> {
}
