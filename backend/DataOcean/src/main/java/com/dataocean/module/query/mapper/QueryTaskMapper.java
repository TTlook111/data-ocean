package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.QueryTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询任务 Mapper
 */
@Mapper
public interface QueryTaskMapper extends BaseMapper<QueryTask> {
}
